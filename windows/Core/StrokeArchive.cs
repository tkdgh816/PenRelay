using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text.Json;
using Microsoft.Data.Sqlite;

namespace PenRelay.Core;

// 한 피어의 수신 세션이 쓴다. 독자는 배치가 커밋될 때까지 직전 상태를 읽는다.
internal sealed class StrokeArchive : IDisposable
{
  private readonly SqliteConnection db;
  private readonly string folder;
  private StoredInkPage? displayed;
  private readonly Dictionary<Guid,StoredStroke?> displayedEdits = [];
  private SqliteTransaction? write;
  private readonly record struct Cursor(string Book, long Seq, string Token)
  {
    public object Wire => new { book = Book, seq = Seq, token = Token };
    public static Cursor Parse(JsonElement value) =>
      new(value.GetProperty("book").GetString()!, value.GetProperty("seq").GetInt64(), value.GetProperty("token").GetString()!);
  }

  private sealed record Pending(Guid Id, Guid Canvas, long Layer, byte[] Hash);
  private Cursor basis, target;
  private Guid batch;
  private bool collecting;
  private Dictionary<Guid,PageVersion> pages = [];
  private readonly HashSet<Guid> modified = [];
  private readonly Queue<Pending> missing = [];
  private Dictionary<Guid,Guid>? unmatched;
  private MemoryStream? incoming;
  private int expectedSize;
  public bool Active => write != null;

  internal StrokeArchive(string folder)
  {
    this.folder = folder;
    Directory.CreateDirectory(folder);
    db = Open(folder, false);
    Execute("PRAGMA journal_mode=WAL");
    using var setup = db.BeginTransaction();
    using var schema = db.CreateCommand();
    schema.Transaction = setup;
    schema.CommandText = """
      CREATE TABLE IF NOT EXISTS state(id INTEGER PRIMARY KEY,book TEXT,seq INTEGER,token TEXT,stamp TEXT,selected TEXT);
      CREATE TABLE IF NOT EXISTS layout(id INTEGER PRIMARY KEY,groupsJson TEXT,titlesJson TEXT);
      CREATE TABLE IF NOT EXISTS canvas(id TEXT PRIMARY KEY,version TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS stroke(id TEXT PRIMARY KEY,canvas TEXT NOT NULL,layer INTEGER NOT NULL,hash BLOB NOT NULL,body BLOB NOT NULL);
      CREATE INDEX IF NOT EXISTS drawing_order ON stroke(canvas,layer);
      """;
    schema.ExecuteNonQuery();
    setup.Commit();
  }

  private static string PathIn(string folder) => Path.Combine(folder,"stroke-library.db");
  private static SqliteConnection Open(string folder, bool readOnly)
  {
    var connection = new SqliteConnection(new SqliteConnectionStringBuilder {
      DataSource = PathIn(folder), Mode = readOnly ? SqliteOpenMode.ReadOnly : SqliteOpenMode.ReadWriteCreate
    }.ToString());
    connection.Open();
    return connection;
  }

  private static SqliteCommand Query(SqliteConnection connection, SqliteTransaction? transaction, string sql, params object[] args)
  {
    var command = connection.CreateCommand();
    command.Transaction = transaction;
    command.CommandText = sql;
    for (int i = 0; i < args.Length; i++) command.Parameters.AddWithValue("$p" + i,args[i]);
    return command;
  }
  private void Execute(string sql, params object[] args)
  {
    using var command = Query(db,write,sql,args);
    command.ExecuteNonQuery();
  }
  private Cursor Current()
  {
    using var command = Query(db,write,"SELECT book,seq,token FROM state WHERE id=1");
    using var reader = command.ExecuteReader();
    return reader.Read() ? new(reader.GetString(0),reader.GetInt64(1),reader.GetString(2)) : new("",0,"");
  }
  internal object Position => Current().Wire;

  internal void Begin(Guid batchId, JsonElement header, PageVersion[] next)
  {
    write = db.BeginTransaction();
    basis = Cursor.Parse(header.GetProperty("base"));
    target = Cursor.Parse(header.GetProperty("cursor"));
    bool reconcile = header.GetProperty("reconcile").GetBoolean();
    if (basis != Current() || !Guid.TryParse(target.Book,out _) || !Guid.TryParse(target.Token,out _) ||
        target.Seq < 1 || (!reconcile && (target.Book != basis.Book || target.Seq < basis.Seq ||
        (target.Seq == basis.Seq && target.Token != basis.Token)))) throw new InvalidDataException("필기 동기화 기준 오류");
    batch = batchId;
    collecting = true;
    pages = next.ToDictionary(p => p.Id);
    modified.Clear();
    displayedEdits.Clear();
    displayed = reconcile ? null : StoredInkCache.Selected(folder);
    if (reconcile)
    {
      unmatched = [];
      using var command = Query(db,write,"SELECT id,canvas FROM stroke");
      using var rows = command.ExecuteReader();
      while (rows.Read()) unmatched.Add(Guid.Parse(rows.GetString(0)),Guid.Parse(rows.GetString(1)));
    }
  }

  internal CanvasReply? Inventory(JsonElement message)
  {
    if (!collecting || message.GetProperty("batch").GetGuid() != batch) throw new InvalidDataException("획 목록 순서 오류");
    if (message.TryGetProperty("records",out var records))
    {
      foreach (var row in records.EnumerateArray())
      {
        var id = row[0].GetGuid();
        unmatched?.Remove(id);
        if (row.GetArrayLength() == 1)
        {
          if (displayed?.Strokes.ContainsKey(id) == true) displayedEdits[id] = null;
          Execute("DELETE FROM stroke WHERE id=$p0",id.ToString());
          continue;
        }
        var canvas = row[1].GetGuid();
        var layer = row[2].GetInt64();
        var hash = row[3].GetBytesFromBase64();
        if (!pages.ContainsKey(canvas) || layer < 1 || hash.Length != 32) throw new InvalidDataException("획 목록 항목 오류");
        using var command = Query(db,write,"SELECT hash FROM stroke WHERE id=$p0 AND canvas=$p1 AND layer=$p2",
          id.ToString(),canvas.ToString(),layer);
        if (command.ExecuteScalar() is byte[] stored && stored.AsSpan().SequenceEqual(hash)) continue;
        missing.Enqueue(new(id,canvas,layer,hash));
        modified.Add(canvas);
      }
      return null;
    }

    if (!message.GetProperty("recordsEnd").GetBoolean()) throw new InvalidDataException("획 목록 종료 오류");
    collecting = false;
    if (unmatched != null)
    {
      foreach (var (id,canvas) in unmatched)
      {
        Execute("DELETE FROM stroke WHERE id=$p0",id.ToString());
        modified.Add(canvas);
      }
      unmatched = null;
    }
    return RequestNext();
  }

  internal bool Ready => Active && !collecting && missing.Count == 0;

  private CanvasReply? RequestNext() => missing.TryPeek(out var part)
    ? new(FrameKind.PageReply,JsonSerializer.SerializeToUtf8Bytes(new { batch, stroke = part.Id })) : null;

  internal CanvasReply? Chunk(byte[] bytes)
  {
    if (collecting || !missing.TryPeek(out var part) || bytes.Length <= 24 || new Guid(bytes.AsSpan(0,16),true) != part.Id)
      throw new InvalidDataException("요청하지 않은 획 조각");
    int offset = BinaryPrimitives.ReadInt32BigEndian(bytes.AsSpan(16,4));
    int size = BinaryPrimitives.ReadInt32BigEndian(bytes.AsSpan(20,4));
    if (size is < 1 or > 64*1024*1024 || offset != (incoming?.Length ?? 0) || bytes.Length-24 > size-offset ||
      (incoming != null && expectedSize != size)) throw new InvalidDataException("획 조각 범위 오류");
    incoming ??= new MemoryStream();
    expectedSize = size;
    incoming.Write(bytes,24,bytes.Length-24);
    if (incoming.Length < size) return null;
    var payload = incoming.ToArray();
    incoming.Dispose();
    incoming = null;
    if (!SHA256.HashData(payload).AsSpan().SequenceEqual(part.Hash)) throw new InvalidDataException("획 해시 불일치");
    var ink = InkPage.DecodeStroke(payload,pages[part.Canvas],displayed?.Version.Id == part.Canvas);
    if (ink != null) displayedEdits[part.Id] = new(part.Layer,ink);
    else if (displayed?.Strokes.ContainsKey(part.Id) == true) displayedEdits[part.Id] = null;
    Execute("INSERT OR REPLACE INTO stroke VALUES($p0,$p1,$p2,$p3,$p4)",part.Id.ToString(),part.Canvas.ToString(),part.Layer,part.Hash,payload);
    missing.Dequeue();
    return RequestNext();
  }

  internal PageCatalog Commit(PageCatalog catalog, PageCatalog? prior)
  {
    // 구형 파일 목록만 남았으면 SQLite에 남은 이전 행도 정리해야 한다.
    var previous = prior != null && prior.Pages.Length > 0 && prior.Pages.All(p => p.ArchiveKey != null)
      ? prior.Pages.ToDictionary(p => p.Id) : Versions(db,write).ToDictionary(p => p.Id);
    foreach (var old in previous.Keys.Except(pages.Keys))
    {
      Execute("DELETE FROM stroke WHERE canvas=$p0",old.ToString());
      Execute("DELETE FROM canvas WHERE id=$p0",old.ToString());
    }
    var versions = new List<PageVersion>();
    foreach (var page in catalog.Pages)
    {
      if (previous.TryGetValue(page.Id,out var old) && (old with { ArchiveKey = null }) == page && !modified.Contains(page.Id))
      {
        versions.Add(old);
        continue;
      }
      var version = page with { ArchiveKey = Guid.NewGuid().ToString("N") };
      versions.Add(version);
      Execute("INSERT OR REPLACE INTO canvas VALUES($p0,$p1)",page.Id.ToString(),JsonSerializer.Serialize(version));
    }
    string groups = JsonSerializer.Serialize(catalog.Groups);
    string titles = JsonSerializer.Serialize(catalog.Titles);
    bool sameArchive = prior != null && prior.Pages.All(page => page.ArchiveKey != null) && basis.Book == target.Book;
    if (!sameArchive || groups != JsonSerializer.Serialize(prior!.Groups) || titles != JsonSerializer.Serialize(prior.Titles))
    {
      Execute("""
        INSERT INTO layout VALUES(1,$p0,$p1) ON CONFLICT(id) DO UPDATE SET groupsJson=excluded.groupsJson,titlesJson=excluded.titlesJson
        WHERE groupsJson<>excluded.groupsJson OR titlesJson<>excluded.titlesJson
        """,groups,titles);
    }
    string stamp = catalog.SyncedAt.ToString("O");
    if (sameArchive && basis == target && catalog.Selected == prior!.Selected)
    {
      // 내용이 같은 재접속에서도 마지막 동기화 시각은 표시하고 저장한다.
      Execute("UPDATE state SET stamp=$p0 WHERE id=1",stamp);
    }
    else
    {
      Execute("INSERT OR REPLACE INTO state VALUES(1,$p0,$p1,$p2,$p3,$p4)",target.Book,target.Seq,target.Token,
        stamp,catalog.Selected?.ToString() ?? "");
    }
    write!.Commit();
    write.Dispose();
    write = null;
    if (displayed != null && previous.GetValueOrDefault(displayed.Version.Id) == displayed.Version &&
      versions.FirstOrDefault(p => p.Id == displayed.Version.Id) is { } next && next != displayed.Version)
    {
      StoredInkCache.Publish(displayed,displayed.Apply(next,displayedEdits));
    }
    displayed = null;
    displayedEdits.Clear();
    return catalog with { Pages = versions.ToArray() };
  }

  private static List<PageVersion> Versions(SqliteConnection db, SqliteTransaction? tx)
  {
    using var sql = Query(db,tx,"SELECT version FROM canvas ORDER BY rowid");
    using var rows = sql.ExecuteReader();
    var pages = new List<PageVersion>();
    while (rows.Read()) pages.Add(JsonSerializer.Deserialize<PageVersion>(rows.GetString(0))!);
    return pages;
  }

  internal static PageCatalog? ReadCatalog(string folder)
  {
    if (!File.Exists(PathIn(folder))) return null;
    using var db = Open(folder,true);
    using var tx = db.BeginTransaction(deferred:true);
    using var exists = Query(db,tx,"SELECT 1 FROM sqlite_master WHERE type='table' AND name='state'");
    if (exists.ExecuteScalar() == null) return null;
    using var sql = Query(db,tx,"SELECT stamp,selected,groupsJson,titlesJson FROM state JOIN layout USING(id)");
    using var row = sql.ExecuteReader();
    if (!row.Read()) return null;
    var stamp = DateTimeOffset.Parse(row.GetString(0));
    Guid? selected = Guid.TryParse(row.GetString(1),out var id) ? id : null;
    var groups = JsonSerializer.Deserialize<CanvasGroup[]>(row.GetString(2));
    var titles = JsonSerializer.Deserialize<Dictionary<Guid,string>>(row.GetString(3));
    row.Close();
    var versions = Versions(db,tx).ToDictionary(p => p.Id);
    return new(stamp,groups!.SelectMany(g => g.Canvases).Select(id => versions[id]).ToArray(),titles,groups,selected);
  }

  internal static StoredInkPage LoadInk(string folder, PageVersion version, CancellationToken token)
  {
    using var db = Open(folder,true);
    using var tx = db.BeginTransaction(deferred:true);
    using var sql = Query(db,tx,"SELECT version FROM canvas WHERE id=$p0",version.Id.ToString());
    if (sql.ExecuteScalar() is not string json || JsonSerializer.Deserialize<PageVersion>(json) != version)
      throw new IOException("캔버스가 새 버전으로 갱신되었습니다.");
    using var command = Query(db,tx,"SELECT id,layer,body FROM stroke WHERE canvas=$p0",version.Id.ToString());
    using var rows = command.ExecuteReader();
    var strokes = new Dictionary<Guid,StoredStroke>();
    while (rows.Read())
    {
      token.ThrowIfCancellationRequested();
      strokes.Add(Guid.Parse(rows.GetString(0)),new(rows.GetInt64(1),InkPage.DecodeStroke((byte[])rows[2],version,true)!));
    }
    return new(folder,version,strokes);
  }

  internal static void Retire(string folder)
  {
    if (!File.Exists(PathIn(folder))) return;
    using var db = Open(folder,false);
    using var command = db.CreateCommand();
    command.CommandText = "DELETE FROM state WHERE id=1";
    command.ExecuteNonQuery();
  }

  public void Dispose()
  {
    incoming?.Dispose();
    write?.Dispose();
    db.Dispose();
  }
}
