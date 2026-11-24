using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Numerics;
using System.Threading;
using System.Threading.Tasks;

using PenRelay.Core;

using Microsoft.Graphics.Canvas;
using Microsoft.Graphics.Canvas.UI;
using Microsoft.Graphics.Canvas.UI.Xaml;

using Windows.Foundation;
using Windows.Graphics.DirectX;
using Windows.UI;

namespace PenRelay.Desktop;

public sealed partial class CanvasViewer : Microsoft.UI.Xaml.Controls.UserControl
{
  private CanvasReplica? replica;
  private PenRelay.Core.CanvasImage? displayed;
  private CanvasBitmap? bitmap;
  internal PenRelay.Core.CanvasImage? CommittedImage => replica?.CommittedImage;
  private sealed record RenderRequest(CanvasDevice Device, PenRelay.Core.CanvasImage Image, InkPage? Ink, float Dpi);
  private RenderRequest? desiredRender, completedRender, failedRender;
  private CancellationTokenSource? rendering;
  private PenRelay.Core.CanvasImage? presentedImage;
  private int queued;
  private volatile bool renderingEnabled = true;
  internal void SetRenderingEnabled(bool enabled)
  {
    if (renderingEnabled == enabled || detached) return;
    renderingEnabled = enabled;
    pendingLines.Clear();
    if (enabled) Refresh();
    else rendering?.Cancel();
  }

  private bool previewVisible, fixture, benchmark, detached;
  public event Action<PenRelay.Core.CanvasImage>? Presented;
  private Guid? selectedPage;
  private bool allowLive = true;
  private bool expectsVectors;
  private PenRelay.Core.CanvasImage? savedImage;
  private InkPage? inkPage;
  private LibraryUpdate? liveLibrary;
  private string? loadingInk;
  private PageVersion? liveVersion;
  private InkPage? liveInk;
  private readonly Dictionary<Guid, PreviewLine> pendingLines = new();
  private Guid? previewDocument;
  private long previewGeneration = -1;
  private PreviewDrawing previewDrawing = new();
  internal void FollowLibrary(LibraryUpdate? update)
  {
    if (liveLibrary?.Folder != update?.Folder)
    {
      loadingInk = null;
      liveInk = null;
      liveVersion = null;
    }
    liveLibrary = update;
    Refresh();
  }
  private async Task LoadLiveInk(string file, PageVersion version)
  {
    try
    {
      var ink = await Task.Run(() => version.ArchiveKey == null ? InkPage.Read(file, version) :
        PageLibrary.ReadStoredInk(Path.GetDirectoryName(file)!,version));
      if (detached || loadingInk != file)
      {
        return;
      }

      liveInk = ink;
      liveVersion = version;
      Refresh();
    }
    catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or InvalidDataException) { }
  }
  internal void ShowPage(Guid? page, bool live, PenRelay.Core.CanvasImage? saved, InkPage? ink, bool expectsVectors)
  {
    if (selectedPage == page && allowLive == live && ReferenceEquals(savedImage, saved) &&
      ReferenceEquals(inkPage, ink) && this.expectsVectors == expectsVectors)
    {
      return;
    }

    selectedPage = page;
    allowLive = live;
    this.expectsVectors = expectsVectors;
    savedImage = saved;
    inkPage = ink;
    Refresh();
  }
  public CanvasViewer() => InitializeComponent();
  public void Bind(CanvasReplica? next, bool fixture = false, bool benchmark = false)
  {
    if (replica != null)
    {
      replica.Changed -= Refresh;
      replica.PreviewsCommitted -= KeepCommittedPreviews;
    }

    this.fixture = fixture;
    this.benchmark = benchmark;
    replica = next;
    pendingLines.Clear();
    if (replica != null)
    {
      replica.Changed += Refresh;
      replica.PreviewsCommitted += KeepCommittedPreviews;
    }

    displayed = null;
    presentedImage = null;
    completedRender = desiredRender = failedRender = null;
    rendering?.Cancel();
    Refresh();
  }
  public void Detach()
  {
    detached = true;
    rendering?.Cancel();
    if (replica != null)
    {
      replica.Changed -= Refresh;
      replica.PreviewsCommitted -= KeepCommittedPreviews;
    }

    replica = null;
    previewDrawing.Dispose();
    Paper.RemoveFromVisualTree();
    bitmap?.Dispose();
  }
  private void KeepCommittedPreviews(CanvasReplica source, Guid document, PreviewLine[] lines)
  {
    if (!renderingEnabled) return;
    long generation = source.Generation;
    // 두 Draw 사이에서 확정된 짧은 획과 마지막 좌표도 새 화면으로 교체할 때까지 보존한다.
    DispatcherQueue.TryEnqueue(() =>
    {
      if (!renderingEnabled || source != replica || generation != source.Generation || !allowLive || displayed?.Document != document ||
        (selectedPage != null && selectedPage != document))
      {
        return;
      }
      if (previewDocument != document || previewGeneration != generation)
      {
        pendingLines.Clear();
        previewDocument = document;
        previewGeneration = generation;
      }
      foreach (var line in lines)
      {
        if (line.Revision > displayed.Revision) pendingLines[line.Id] = line;
      }
      Refresh();
    });
  }
  private void Refresh()
  {
    if (renderingEnabled && Interlocked.Exchange(ref queued, 1) == 0)
    {
      DispatcherQueue.TryEnqueue(() =>
      {
        Interlocked.Exchange(ref queued, 0);
        if (renderingEnabled && !detached)
        {
          Paper.Invalidate();
        }
      });
    }
  }
  private void ResourcesCreated(CanvasControl sender, CanvasCreateResourcesEventArgs args)
  {
    previewDrawing.Dispose();
    previewDrawing = new();
    args.TrackAsyncAction(previewDrawing.Load(sender.Device).AsAsyncAction());
    bitmap?.Dispose();
    bitmap = null;
    displayed = null;
    completedRender = desiredRender = failedRender = null;
    rendering?.Cancel();
  }
  private static CanvasBitmap Render(RenderRequest request, CancellationToken token)
  {
    var image = request.Image;
    if (request.Ink is { } ink)
    {
      return InkDrawing.Render(request.Device, ink, request.Dpi, token);
    }

    var rgba = image.Rgba;
    for (int p = 0; p < rgba.Length; p += 4)
    {
      if ((p & 0xffff) == 0)
      {
        token.ThrowIfCancellationRequested();
      }

      int alpha = rgba[p + 3];
      if (alpha == 255 || (rgba[p] | rgba[p + 1] | rgba[p + 2]) == 0)
      {
        continue;
      }

      if (ReferenceEquals(rgba, image.Rgba))
      {
        rgba = (byte[])rgba.Clone();
      }

      for (int c = 0; c < 3; c++)
      {
        rgba[p + c] = (byte)((rgba[p + c] * alpha + 127) / 255);
      }
    }
    return CanvasBitmap.CreateFromBytes(request.Device, rgba, image.Width, image.Height,
        DirectXPixelFormat.R8G8B8A8UIntNormalized, 96, CanvasAlphaMode.Premultiplied);
  }
  private async Task Prepare(RenderRequest request)
  {
    using var work = new CancellationTokenSource();
    rendering = work;
    try
    {
      var result = await Task.Run(() => Render(request, work.Token), work.Token);
      if (work.IsCancellationRequested || detached || desiredRender != request)
      {
        result.Dispose();
        return;
      }
      bitmap?.Dispose();
      bitmap = result;
      displayed = request.Image;
      completedRender = request;
    }
    catch (OperationCanceledException) when (work.IsCancellationRequested) { }
    catch (Exception ex)
    {
      failedRender = request;
      System.Diagnostics.Trace.TraceError("캔버스 표시 실패 · {0}", ex.Message);
    }
    finally
    {
      rendering = null;
      if (!detached)
      {
        Refresh();
      }
    }
  }
  private void Draw(CanvasControl sender, CanvasDrawEventArgs args)
  {
    if (!renderingEnabled || detached) return;
    var ds = args.DrawingSession;
    var state = allowLive ? replica?.Read() : null;
    var received = state?.Image;
    if (received != null && liveLibrary?.Catalog is { } catalog)
    {
      var version = catalog.Pages.FirstOrDefault(p => p.HasVectors && p.Id == received.Document);
      if (version != null)
      {
        string file = PageLibrary.InkPath(liveLibrary.Folder, version);
        if (loadingInk != file)
        {
          loadingInk = file;
          _ = LoadLiveInk(file, version);
        }
      }
    }
    bool useLive = received != null && (selectedPage == null || received.Document == selectedPage) &&
            (savedImage == null || received.Revision >= savedImage.Revision);
    var image = useLive ? received : savedImage;
    if (image == null)
    {
      desiredRender = null;
      rendering?.Cancel();
      return;
    }
    float scale = (float)Math.Min(sender.ActualWidth / image.Width, sender.ActualHeight / image.Height);
    if (scale <= 0)
    {
      return;
    }

    var vectors = savedImage?.Document == image.Document && savedImage.Revision == image.Revision ? inkPage : null;
    if (vectors == null && liveVersion?.Id == image.Document && liveVersion.Revision == image.Revision)
    {
      vectors = liveInk;
    }
    float physicalScale = scale * sender.Dpi / 96f;
    var pixels = new RenderRequest(sender.Device, image, null, 96f);
    RenderRequest? request = pixels;
    if (physicalScale > 1)
    {
      float dpi = 24f * MathF.Ceiling(physicalScale * 4);
      if (vectors != null)
      {
        var sharp = new RenderRequest(sender.Device, image, vectors, dpi);
        request = sharp == failedRender ? pixels : sharp;
      }
      else if (expectsVectors && displayed?.Document == image.Document &&
        displayed.Width == image.Width && displayed.Height == image.Height && displayed.Revision <= image.Revision)
      {
        // 픽셀 확정과 벡터 동기화는 독립적이다. 따라잡은 벡터 버전까지만 화면을 전진시킨다.
        request = null;
        if (inkPage != null && savedImage?.Document == image.Document &&
          savedImage.Revision >= displayed.Revision && savedImage.Revision < image.Revision)
        {
          request = new RenderRequest(sender.Device, savedImage, inkPage, dpi);
        }
        else if (desiredRender is { Ink: not null } preparing && preparing.Image.Document == image.Document &&
          preparing.Image.Revision <= image.Revision && preparing.Dpi == dpi)
        {
          request = preparing;
        }
      }
    }
    if (desiredRender != request)
    {
      desiredRender = request;
      rendering?.Cancel();
    }
    if (request != null && completedRender != request && failedRender != request && rendering == null)
    {
      _ = Prepare(request);
    }

    if (bitmap == null || displayed == null || displayed.Document != image.Document ||
            displayed.Width != image.Width || displayed.Height != image.Height ||
            (image.Document == Guid.Empty && displayed != image))
    {
      return;
    }

    bool changed = presentedImage != displayed;
    presentedImage = displayed;
    if (changed && useLive)
    {
      Presented?.Invoke(displayed);
    }

    ds.Transform = Matrix3x2.CreateScale(scale) * Matrix3x2.CreateTranslation(
            ((float)sender.ActualWidth - image.Width * scale) / 2, ((float)sender.ActualHeight - image.Height * scale) / 2);
    var page = new Rect(0, 0, image.Width, image.Height);
    using var clip = ds.CreateLayer(1f, page);
    ds.FillRectangle(page, Color.FromArgb(255, 255, 255, 255));
    ds.DrawImage(bitmap, page, page);
    bool previewPage = received?.Document == image.Document;
    var incoming = previewPage ? state!.Preview : Array.Empty<PreviewLine>();
    if (previewDocument != image.Document || !previewPage || previewGeneration != state!.Generation)
    {
      pendingLines.Clear();
    }

    previewGeneration = state?.Generation ?? -1;
    previewDocument = image.Document;
    IEnumerable<PreviewLine> previews = incoming;
    if (previewPage)
    {
      // 픽셀 ACK로 미리보기가 끝나도 같은 버전의 벡터가 준비될 때까지 이미 보인 획을 유지한다.
      long drawnRevision = displayed.Revision;
      foreach (var line in incoming)
      {
        if (line.Revision > drawnRevision)
        {
          pendingLines[line.Id] = line;
        }
      }

      foreach (var line in pendingLines.Values.ToArray())
      {
        if (line.Revision <= Math.Max(drawnRevision, state!.OverlayBase) ||
                    (line.Revision > received!.Revision && !incoming.Any(p => p.Id == line.Id)))
        {
          pendingLines.Remove(line.Id);
        }
      }

      previews = drawnRevision < state!.OverlayBase ? Array.Empty<PreviewLine>()
        : pendingLines.Values.OrderBy(line => line.Revision).ToArray();
    }
    previewDrawing.Draw(ds, previews);
    bool visible = previews.Any();
    if (benchmark && changed)
    {
      var elapsed = System.Diagnostics.Stopwatch.GetElapsedTime(displayed.PublishedAt);
      Console.WriteLine($"그리기 {displayed.Revision} {elapsed.Ticks / 10}");
    }
    if (fixture && (changed || visible != previewVisible))
    {
      Console.WriteLine($"표시 {displayed.Revision} {displayed.Hash} {previews.Count()}");
    }

    previewVisible = visible;
  }
}
