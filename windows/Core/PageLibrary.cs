using System.Buffers.Binary;
using System.Text.Json;

namespace PenRelay.Core;

public sealed record PageVersion(Guid Id, long Revision, int Width = 1024, int Height = 640, bool HasVectors = false,
  bool Textured = false, string? ArchiveKey = null);
public sealed record CanvasGroup(Guid Id, Guid[] Canvases);
public sealed record PageCatalog(DateTimeOffset SyncedAt, PageVersion[] Pages, Dictionary<Guid,string>? Titles = null,
  CanvasGroup[]? Groups = null, Guid? Selected = null)
{
  public CanvasGroup[] PageGroups() => Groups ?? Pages.Select(p => new CanvasGroup(p.Id,[p.Id])).ToArray();
}
public sealed record LibraryUpdate(string Folder, PageCatalog? Catalog, bool Transferring, Guid? Selection = null);

// 한 세션의 전송을 순서대로 처리한다. 마지막 파일까지 저장한 뒤에만 목록을 교체한다.
public sealed class PageLibrary(string folder, Action<LibraryUpdate> changed) : IDisposable
{
  private StrokeArchive? archive;
  public object EnableStrokeSync()
  {
    archive = new StrokeArchive(folder);
    return archive.Position;
  }
  public static InkPage ReadStoredInk(string directory, PageVersion page, CancellationToken token = default,
    bool forThumbnail = false) => StoredInkCache.Read(directory,page,forThumbnail,token);
  private PageVersion[]? proposed;
  private Dictionary<Guid,string>? titles;
  private readonly Queue<(PageVersion Page, bool Ink)> missing = new();
  private CanvasGroup[]? groups;
  private Guid? selected;
  private long sequence = -1;
  private string? requested;
  public void RequestSelection(Guid id) => Interlocked.Exchange(ref requested,id.ToString());
  public string? TakeRequest() => groups == null ? null : Interlocked.Exchange(ref requested,null);
  private bool ReadSelection(JsonElement body)
  {
    if (!body.TryGetProperty("selected",out var value)) return false;
    long next = body.GetProperty("navSeq").GetInt64();
    if (next <= sequence) return false;
    sequence = next;
    selected = value.GetGuid();
    return true;
  }
  private Guid batch;
  private FileStream? stream;
  private string? temp;
  private int length;
  private PageCatalog? catalog = Read(folder);
  public static string ImagePath(string directory, PageVersion page) =>
    Path.Combine(directory, $"{page.Id:N}-{page.Revision}{(page.ArchiveKey != null ? "-" + page.ArchiveKey : page.Textured ? "-ink2" : page.HasVectors ? "-ink1" : "")}.png");
  public static string InkPath(string directory,PageVersion page) =>
    Path.ChangeExtension(ImagePath(directory,page),".ink");
  public static PageCatalog? Read(string directory)
  {
    var stored = StrokeArchive.ReadCatalog(directory);
    if (stored != null) return stored;
    var path = Path.Combine(directory, "pages.json");
    return File.Exists(path) ? JsonSerializer.Deserialize<PageCatalog>(File.ReadAllBytes(path)) : null;
  }
  public CanvasReply? Accept(Frame item)
  {
    if (item.Kind == FrameKind.PageCurrent)
    {
      using var receipt = JsonDocument.Parse(item.Payload);
      if (receipt.RootElement.TryGetProperty("selected",out _))
      {
        if (ReadSelection(receipt.RootElement) && catalog?.Pages.Any(p => p.Id == selected) == true)
          changed(new(folder,catalog,proposed != null,selected));
        return null;
      }
      if (catalog == null || proposed != null || receipt.RootElement.GetProperty("batch").GetGuid() != batch)
        throw new InvalidDataException("일치하지 않는 페이지 목록 확인");
      changed(new(folder,catalog,false));
      return null;
    }
    if (item.Kind == FrameKind.PageList)
    {
      using var json = JsonDocument.Parse(item.Payload);
      var root = json.RootElement;
      if (archive?.Active == true)
        return FinishStrokeBatch(archive.Inventory(root));
      if (proposed != null) throw new InvalidDataException("페이지 목록 전송 중복");
      batch = root.GetProperty("batch").GetGuid();
      proposed = root.GetProperty("pages").EnumerateArray().Select(p =>
        p.GetArrayLength() switch {
          2 => new PageVersion(p[0].GetGuid(),p[1].GetInt64()),
          4 => new PageVersion(p[0].GetGuid(),p[1].GetInt64(),p[2].GetInt32(),p[3].GetInt32(),true),
          5 when p[4].GetString() == "ink2" => new PageVersion(p[0].GetGuid(),p[1].GetInt64(),p[2].GetInt32(),p[3].GetInt32(),true,true),
          _ => throw new InvalidDataException("페이지 목록 형식 오류")
        }).ToArray();
      if (proposed.Any(p => p.Revision < 0 || p.Width is < 1 or > 2048 || p.Height is < 1 or > 2048) ||
        proposed.Select(p => p.Id).Distinct().Count() != proposed.Length)
        throw new InvalidDataException("페이지 목록 형식 오류");
      groups = null;
      if (root.TryGetProperty("groups",out var rows))
      {
        groups = rows.EnumerateArray().Select(row => new CanvasGroup(row.GetProperty("id").GetGuid(),
          row.GetProperty("canvases").EnumerateArray().Select(id => id.GetGuid()).ToArray())).ToArray();
        var members = groups.SelectMany(g => g.Canvases).ToArray();
        if (groups.Any(g => g.Canvases.Length == 0) || groups.Select(g => g.Id).Distinct().Count() != groups.Length ||
          members.Length != proposed.Length || !members.ToHashSet().SetEquals(proposed.Select(p => p.Id)))
          throw new InvalidDataException("캔버스 구성 오류");
        ReadSelection(root);
      }
      titles = null;
      if (root.TryGetProperty("titles", out var names))
      {
        titles = new();
        var ids = proposed.Select(p => p.Id).ToHashSet();
        foreach (var name in names.EnumerateObject())
        {
          var title = name.Value.GetString();
          if (!Guid.TryParse(name.Name, out var id) || !ids.Contains(id) || string.IsNullOrWhiteSpace(title) ||
            title.Length > 80 || !titles.TryAdd(id,title)) throw new InvalidDataException("페이지 제목 형식 오류");
        }
      }
      if (archive != null)
      {
        if (groups == null || proposed.Any(p => !p.HasVectors)) throw new InvalidDataException("획 보관함 페이지 구성 오류");
        archive.Begin(batch,root,proposed);
        changed(new(folder,catalog,true));
        return null;
      }
      Directory.CreateDirectory(folder);
      var known = (catalog?.Pages ?? []).ToHashSet();
      foreach (var p in proposed)
      {
        if (!known.Contains(p) || !File.Exists(ImagePath(folder,p))) missing.Enqueue((p,false));
        if (p.HasVectors && (!known.Contains(p) || !File.Exists(InkPath(folder,p)))) missing.Enqueue((p,true));
      }
      changed(new(folder,catalog,true));
      return Next();
    }
    if (archive != null) return FinishStrokeBatch(archive.Chunk(item.Payload));
    if (proposed == null || !missing.TryPeek(out var part) || item.Payload.Length <= 24)
      throw new InvalidDataException("요청하지 않은 페이지 조각");
    var (page,ink) = part;
    var bytes = item.Payload.AsSpan();
    int offset = BinaryPrimitives.ReadInt32BigEndian(bytes[16..]);
    int total = BinaryPrimitives.ReadInt32BigEndian(bytes[20..]);
    if (new Guid(bytes[..16],true) != page.Id || offset != (stream?.Position ?? 0) ||
      total < (ink ? 16 : 33) || total > (ink ? 64*1024*1024 : page.Width*page.Height*5+65536) ||
      (stream != null && length != total) || bytes.Length-24 > total-offset)
      throw new InvalidDataException("페이지 길이 또는 순서 오류");
    if (stream == null)
    {
      ReadOnlySpan<byte> signature = [137,80,78,71,13,10,26,10];
      if (!ink && (bytes.Length < 48 || !bytes.Slice(24,8).SequenceEqual(signature) ||
        !bytes.Slice(36,4).SequenceEqual("IHDR"u8) ||
        BinaryPrimitives.ReadInt32BigEndian(bytes[40..]) != page.Width ||
        BinaryPrimitives.ReadInt32BigEndian(bytes[44..]) != page.Height))
        throw new InvalidDataException("페이지 PNG 머리글 오류");
      temp = Path.Combine(folder,"incoming.tmp");
      stream = File.Create(temp);
      length = total;
    }
    stream.Write(bytes[24..]);
    if (stream.Position < length) return null;
    stream.Flush(true); stream.Dispose(); stream = null;
    if (ink) InkPage.Validate(temp!,page);
    File.Move(temp!,ink ? InkPath(folder,page) : ImagePath(folder,page),true); temp = null;
    missing.Dequeue();
    return Next();
  }
  private CanvasReply? FinishStrokeBatch(CanvasReply? reply)
  {
    if (!archive!.Ready) return reply;
    catalog = archive.Commit(new(DateTimeOffset.UtcNow,proposed!,titles,groups,selected),catalog);
    proposed = null;
    changed(new(folder,catalog,true,selected));
    return new(FrameKind.PageReply,JsonSerializer.SerializeToUtf8Bytes(new { batch, complete = true }));
  }

  private CanvasReply Next()
  {
    if (missing.TryPeek(out var part))
      return new(FrameKind.PageReply, JsonSerializer.SerializeToUtf8Bytes(new { batch, need = part.Page.Id, format = part.Ink ? (part.Page.Textured ? "ink2" : "ink1") : "png" }));
    var next = new PageCatalog(DateTimeOffset.UtcNow,proposed!,titles,groups,selected);
    var path = Path.Combine(folder,"pages.json");
    File.WriteAllBytes(path + ".tmp",JsonSerializer.SerializeToUtf8Bytes(next));
    File.Move(path + ".tmp",path,true);
    StrokeArchive.Retire(folder);
    var keep = next.Pages.Select(p => ImagePath(folder,p)).ToHashSet();
    foreach (var p in next.Pages.Where(p => p.HasVectors)) keep.Add(InkPath(folder,p));
    foreach (var old in Directory.EnumerateFiles(folder,"*.png").Concat(Directory.EnumerateFiles(folder,"*.ink")))
      if (!keep.Contains(old)) {
        try { File.Delete(old); } catch (IOException) { } catch (UnauthorizedAccessException) { }
      }
    catalog = next; proposed = null;
    changed(new(folder,next,true,selected));
    return new(FrameKind.PageReply,JsonSerializer.SerializeToUtf8Bytes(new { batch, complete = true }));
  }
  public void Dispose()
  {
    archive?.Dispose();
    stream?.Dispose();
    if (temp != null) File.Delete(temp);
  }
}
