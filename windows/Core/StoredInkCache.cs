namespace PenRelay.Core;

// 표시 중인 문서와 최근 축소판 한 장만 보관한다. 게시된 획 목록은 변경하지 않는다.
internal sealed record StoredStroke(long Layer, InkStroke Ink);
internal sealed class StoredInkPage(string folder, PageVersion version, Dictionary<Guid,StoredStroke> strokes)
{
  internal string Folder { get; } = folder;
  internal PageVersion Version { get; } = version;
  internal IReadOnlyDictionary<Guid,StoredStroke> Strokes => strokes;
  internal InkPage Image { get; } = new(version.Width,version.Height,
    strokes.OrderBy(row => row.Value.Layer).ThenBy(row => row.Key).Select(row => row.Value.Ink).ToArray());

  internal StoredInkPage Apply(PageVersion next, Dictionary<Guid,StoredStroke?> edits)
  {
    var updated = new Dictionary<Guid,StoredStroke>(strokes);
    foreach (var (id,stroke) in edits)
    {
      if (stroke == null) updated.Remove(id);
      else updated[id] = stroke;
    }
    return new(Folder,next,updated);
  }
}

internal static class StoredInkCache
{
  private static readonly object gate = new();
  private static readonly SemaphoreSlim reading = new(1);
  private static StoredInkPage? selected, thumbnail;

  internal static StoredInkPage? Selected(string folder)
  {
    lock (gate) return selected?.Folder == folder ? selected : null;
  }

  internal static void Publish(StoredInkPage original, StoredInkPage next)
  {
    lock (gate)
    {
      if (selected == original) selected = next;
      if (thumbnail == original) thumbnail = next;
    }
  }

  internal static InkPage Read(string folder, PageVersion version, bool forThumbnail, CancellationToken token)
  {
    // 화면과 축소판이 동시에 같은 버전을 요청해도 첫 조회의 결과를 공유한다.
    reading.Wait(token);
    try
    {
      StoredInkPage? page;
      lock (gate)
      {
        page = selected?.Folder == folder && selected.Version == version ? selected :
          thumbnail?.Folder == folder && thumbnail.Version == version ? thumbnail : null;
        if (page != null)
        {
          if (!forThumbnail) selected = page;
          return page.Image;
        }
      }
      page = StrokeArchive.LoadInk(folder, version, token);
      token.ThrowIfCancellationRequested();
      lock (gate)
      {
        if (forThumbnail) thumbnail = page;
        else selected = page;
      }
      return page.Image;
    }
    finally { reading.Release(); }
  }
}
