using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Threading;
using System.Threading.Tasks;

using PenRelay.Core;

using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

using Windows.ApplicationModel.DataTransfer;
using Windows.Graphics.Imaging;
using Windows.Storage;
using Windows.Storage.Pickers;
using Windows.Storage.Streams;

namespace PenRelay.Desktop;

public sealed partial class CanvasWorkspace : UserControl
{
  private sealed class PageEntry(string title, string? path, Guid? id = null, long revision = -1)
  {
    public string Title { get; } = title;
    public string? Path { get; set; } = path;
    public Guid? Id { get; } = id;
    public long Revision { get; set; } = revision;
    public PageVersion? Version { get; set; }
    public override string ToString() => Title;
  }
  private string folder = Path.Combine(Environment.GetFolderPath(
      Environment.SpecialFolder.LocalApplicationData), "PenRelay", "Pages");
  private static readonly PageEntry Tablet = new("태블릿 · 실시간 캔버스", null);
  private Window? window;
  private readonly CanvasNavigator navigator = new();
  internal event Action<Guid>? NavigationRequested;
  internal event RoutedEventHandler? OverlayRequested;
  internal void SetOverlayOpen(bool open) => OverlayButton.IsChecked = open;
  private void OverlayClick(object sender, RoutedEventArgs e) => OverlayRequested?.Invoke(sender, e);
  internal event Action? ViewChanged;
  internal Guid? CurrentId => (Library.SelectedItem as PageEntry)?.Id;
  internal void Choose(Guid id)
  {
    if (!presentationVisible)
    {
      deferredSelection = id;
      return;
    }
    var entry = Library.Items.Cast<PageEntry>().FirstOrDefault(p => p.Id == id);
    if (entry != null && !ReferenceEquals(entry, Library.SelectedItem))
    {
      Library.SelectedItem = entry;
    }
  }
  internal void ShowDrawing(bool visible) => Drawing.Visibility = visible ? Visibility.Visible : Visibility.Collapsed;
  internal void Mirror(CanvasViewer target)
  {
    var page = Library.SelectedItem as PageEntry;
    bool live = page == Tablet || (canvasActive && page?.Id != null);
    bool expectsVectors = page?.Version?.HasVectors == true && page.Path != failedPagePath;
    target.ShowPage(page?.Id, live, archiveImage, archiveInk, expectsVectors);
  }
  private bool mainVisible = true, presentationVisible = true;
  private Guid? deferredSelection;

  internal void SetPresentationVisibility(bool main, bool any)
  {
    Live.SetRenderingEnabled(main && Drawing.Visibility == Visibility.Visible);
    if (mainVisible == main && presentationVisible == any) return;
    bool resume = !presentationVisible && any;
    mainVisible = main;
    presentationVisible = any;
    if (!any)
    {
      loading?.Cancel();
      loading = null;
      return;
    }
    if (resume)
    {
      Populate(deferredSelection);
      deferredSelection = null;
      if (loading == null && Library.SelectedItem is PageEntry entry)
      {
        _ = LoadPage(entry, keepImage: entry.Id == archiveImage?.Document);
      }
    }
    UpdateView();
  }

  private bool syncedLibrary;
  private LibraryUpdate? library;
  internal LibraryUpdate? LibrarySnapshot => library;
  private bool canvasActive;
  private bool liveOnly;
  public void SetLiveOnly(bool value)
  {
    liveOnly = value;
    Populate();
  }
  public void SetCanvasActive(bool active)
  {
    canvasActive = active;
    UpdateView();
    UpdateActions();
  }
  private void UpdateView()
  {
    var page = Library.SelectedItem as PageEntry;
    if (!presentationVisible) return;
    if (mainVisible)
    {
      Mirror(Live);
      navigator.Show(liveOnly ? null : library, page?.Id);
    }
    if (CurrentPageTitle.Text != navigator.PageTitle)
    {
      CurrentPageTitle.Text = navigator.PageTitle;
      ToolTipService.SetToolTip(CurrentPageTitle, navigator.PageTitle);
    }
    ViewChanged?.Invoke();
  }
  private void ToolbarResized(object sender, SizeChangedEventArgs e)
  {
    if (syncedLibrary)
    {
      FitCommands(e.NewSize.Width);
    }
  }
  private void FitCommands(double width)
  {
    navigator.Commands.DefaultLabelPosition = width < 550 ? CommandBarDefaultLabelPosition.Collapsed : CommandBarDefaultLabelPosition.Right;
    Commands.DefaultLabelPosition = width < 780 ? CommandBarDefaultLabelPosition.Collapsed : CommandBarDefaultLabelPosition.Right;
  }
  private void ResizeLibrary(object sender, SizeChangedEventArgs e)
  {
    if (Sidebar == null || syncedLibrary)
    {
      return;
    }

    bool narrow = e.NewSize.Width < 570;
    ListColumn.Width = new GridLength(narrow ? 0 : 172);
    Grid.SetColumn(Sidebar, narrow ? 1 : 0);
    Grid.SetRowSpan(Sidebar, narrow ? 1 : 4);
    Sidebar.MaxHeight = narrow ? 145 : double.PositiveInfinity;
  }
  public void SetLibrary(LibraryUpdate update)
  {
    if (library?.Folder == update.Folder && library.Catalog == update.Catalog)
    {
      return;
    }

    if (library?.Folder != update.Folder) deferredSelection = null;
    var preferred = update.Selection ?? (library?.Catalog == null || library.Folder != update.Folder
      ? update.Catalog?.Selected : null);
    library = update;
    if (!presentationVisible && preferred != null) deferredSelection = preferred;
    Populate(preferred);
  }
  private void Populate(Guid? preferred = null)
  {
    if (!presentationVisible) return;
    var previous = Library.SelectedItem as PageEntry;
    var names = new Dictionary<Guid, string>();
    if (library?.Catalog is { } catalog)
    {
      var groups = catalog.PageGroups();
      for (int i = 0; i < groups.Length; i++)
      {
        var ids = groups[i].Canvases;
        var title = catalog.Titles?.GetValueOrDefault(ids[0]) ?? $"노트 {i + 1}";
        for (int j = 0; j < ids.Length; j++)
        {
          names[ids[j]] = ids.Length == 1 ? title : $"{title} · 페이지 {j + 1}";
        }
      }
    }
    var entries = liveOnly ? new List<PageEntry> { Tablet } : library?.Catalog?.Pages.Select((p, i) =>
        new PageEntry(names[p.Id],
            PageLibrary.ImagePath(library.Folder, p), p.Id, p.Revision)
        { Version = p }).ToList() ?? new List<PageEntry>();
    // 목록의 정체성은 페이지 ID와 순서다. 필기 버전과 캐시 경로는 행을 교체하지 않고 갱신한다.
    var rows = Library.Items.Cast<PageEntry>().ToArray();
    bool sameRows = rows.Length == entries.Count && rows.Zip(entries).All(pair =>
        pair.First.Id is Guid id ? pair.Second.Id == id && pair.First.Title == pair.Second.Title : ReferenceEquals(pair.First, pair.Second));
    if (sameRows)
    {
      var target = rows.FirstOrDefault(row => preferred.HasValue && row.Id == preferred) ?? previous;
      for (int i = 0; i < rows.Length; i++)
      {
        var row = rows[i];
        var next = entries[i];
        if (row.Path == next.Path && row.Revision == next.Revision)
        {
          continue;
        }

        row.Path = next.Path;
        row.Revision = next.Revision;
        row.Version = next.Version;
        if (ReferenceEquals(row, previous) && ReferenceEquals(row, target))
        {
          _ = LoadPage(row, keepImage: true);
        }
      }
      if (target != previous) Library.SelectedItem = target;
      UpdateView();
      return;
    }
    Library.Items.Clear();
    foreach (var entry in entries)
    {
      Library.Items.Add(entry);
    }

    Library.SelectedItem = entries.FirstOrDefault(p => preferred.HasValue && p.Id == preferred) ??
      entries.FirstOrDefault(p => previous?.Id != null && p.Id == previous.Id) ?? entries.FirstOrDefault();
    if (Library.SelectedItem == null)
    {
      ClearArchive();
      Feedback.Text = "";
    }
    UpdateView();
    UpdateActions();
  }
  private CanvasImage? liveImage;
  private byte[]? archivePng;
  private CanvasImage? archiveImage;
  private InkPage? archiveInk;
  private string? archivePath;
  private string? failedPagePath;
  private sealed record LoadedPage(string Path, byte[]? Png, CanvasImage Image, InkPage? Ink);
  private LoadedPage? previousPage;
  private CancellationTokenSource? loading;
  private void ClearArchive()
  {
    loading?.Cancel();
    if (archivePath != null && archiveImage != null)
    {
      previousPage = new(archivePath, archivePng, archiveImage, archiveInk);
    }

    archivePng = null;
    archiveImage = null;
    archiveInk = null;
    archivePath = null;
  }
  private bool busy;
  private CanvasShare mainShare = null!;
  public CanvasViewer Viewer => Live;

  public CanvasWorkspace()
  {
    InitializeComponent();
    Live.Presented += image =>
    {
      liveImage = image;
      UpdateActions();
    };
    Library.Items.Add(Tablet);
    Library.SelectedIndex = 0;
  }
  public void Attach(Window owner, string? pageFolder = null, bool synced = false)
  {
    window = owner;
    mainShare = new CanvasShare(owner);
    syncedLibrary = synced;
    if (synced)
    {
      Sidebar.Visibility = Visibility.Collapsed;
      ListColumn.Width = new GridLength(0);
      CanvasLayout.ColumnSpacing = 0;
      Grid.SetColumn(Commands, 2);
      Grid.SetColumnSpan(Commands, 1);
      NavigationCommands.Content = navigator.Commands;
      NavigationSlot.Content = navigator.Filmstrip;
      OverlayButton.Visibility = Visibility.Visible;
      FitCommands(ToolbarRow.ActualWidth);
      navigator.Requested += id => NavigationRequested?.Invoke(id);
      Library.Items.Clear();
      SavePage.Visibility = Visibility.Collapsed;
      UpdateView();
    }
    if (pageFolder != null)
    {
      folder = pageFolder;
    }

    try
    {
      if (!synced && Directory.Exists(folder))
      {
        foreach (var file in Directory.EnumerateFiles(folder, "*.png").OrderDescending())
        {
          Library.Items.Add(Entry(file));
        }
      }

      UpdateActions();
    }
    catch (Exception ex)
    {
      Feedback.Text = "보관함을 읽을 수 없습니다. " + ex.Message;
    }
    owner.Closed += (_, _) =>
    {
      loading?.Cancel();
    };
  }
  public void Bind(CanvasReplica replica, bool fixture = false, bool benchmark = false)
  {
    liveImage = null;
    Live.Bind(replica, fixture, benchmark);
    UpdateActions();
  }
  private void UpdateActions()
  {
    bool live = Equals(Library.SelectedItem, Tablet);
    bool available = live ? liveImage != null : archiveImage != null && archivePath == (Library.SelectedItem as PageEntry)?.Path;
    SavePage.IsEnabled = live && available && !busy;
    CopyPage.IsEnabled = CopyImage() != null && !busy;
    ExportFormats.IsEnabled = SharePage.IsEnabled = available && !busy;
    ExportSvg.IsEnabled = available && archiveInk != null && !busy;
    Empty.Visibility = (live || syncedLibrary && Library.SelectedItem == null) && !available ? Visibility.Visible : Visibility.Collapsed;
  }
  private static PageEntry Entry(string file)
  {
    var name = Path.GetFileNameWithoutExtension(file).Split('_');
    var title = name.Length >= 2 && DateTime.TryParseExact(name[0], "yyyyMMdd-HHmmss-fff", null,
        System.Globalization.DateTimeStyles.None, out var time) ? $"{time:yyyy.MM.dd  HH:mm:ss} · {name[1]}" : name[0];
    return new(title, file);
  }
  private void SelectPage(object sender, SelectionChangedEventArgs e)
  {
    if (Library.SelectedItem is PageEntry entry)
    {
      bool samePage = entry.Path != null && (entry.Path == archivePath ||
          (entry.Id is Guid id && archiveImage?.Document == id &&
           Path.GetDirectoryName(archivePath) == Path.GetDirectoryName(entry.Path)));
      _ = LoadPage(entry, keepImage: samePage);
    }
  }
  private async Task LoadPage(PageEntry entry, bool keepImage)
  {
    var cached = previousPage;
    loading?.Cancel();
    if (!keepImage)
    {
      ClearArchive();
    }

    bool live = entry == Tablet;
    UpdateView();
    Feedback.Text = live ? "펜을 뗀 뒤 확정된 그림을 저장할 수 있습니다." : syncedLibrary ? "" : entry.Title;
    UpdateActions();
    var path = entry.Path;
    var version = entry.Version;
    if (path == null || path == archivePath && (version?.HasVectors != true || archiveInk != null))
    {
      return;
    }

    using var request = new CancellationTokenSource();
    loading = request;
    try
    {
      var current = Live.CommittedImage;
      var page = cached?.Path == path ? cached : await Task.Run(async () =>
      {
        var token = request.Token;
        if (version?.ArchiveKey != null)
        {
          var ink = PageLibrary.ReadStoredInk(Path.GetDirectoryName(path)!,version,token);
          var raster = InkDrawing.Rasterize(version,ink,token);
          return new LoadedPage(path,null,raster,ink);
        }
        var bytes = await File.ReadAllBytesAsync(path, token);
        CanvasImage image;
        if (version != null && current?.Document == version.Id && current.Revision == version.Revision &&
            current.Width == version.Width && current.Height == version.Height)
        {
          image = current;
        }
        else
        {
          using var stream = bytes.AsBufferStream();
          var decoder = await BitmapDecoder.CreateAsync(BitmapDecoder.PngDecoderId, stream);
          token.ThrowIfCancellationRequested();
          var pixels = await decoder.GetPixelDataAsync(BitmapPixelFormat.Rgba8, BitmapAlphaMode.Straight,
              new BitmapTransform(), ExifOrientationMode.IgnoreExifOrientation, ColorManagementMode.ColorManageToSRgb);
          image = new CanvasImage(entry.Id ?? Guid.Empty, entry.Revision, (int)decoder.PixelWidth,
              (int)decoder.PixelHeight, pixels.DetachPixelData(), "");
        }
        return new LoadedPage(path, bytes, image, null);
      }, request.Token);
      request.Token.ThrowIfCancellationRequested();
      archiveImage = page.Image;
      archivePng = page.Png;
      archiveInk = page.Ink;
      archivePath = path;
      UpdateView();
      UpdateActions();
      if (version?.HasVectors == true && archiveInk == null)
      {
        var ink = await Task.Run(() => InkPage.Read(Path.ChangeExtension(path, ".ink"), version), request.Token);
        request.Token.ThrowIfCancellationRequested();
        archiveInk = ink;
        failedPagePath = null;
        UpdateView();
        UpdateActions();
      }
    }
    catch (OperationCanceledException) when (request.IsCancellationRequested) { }
    catch (Exception ex)
    {
      if (!request.IsCancellationRequested)
      {
        failedPagePath = path;
        UpdateView();
        Feedback.Text = "페이지를 열 수 없습니다. " + ex.Message;
      }
    }
    finally
    {
      if (loading == request)
      {
        loading = null;
      }
    }
  }

  // 종이의 흰 배경까지 합성해서 앱에 표시된 색으로 보관한다.
  private static async Task<byte[]> Encode(CanvasImage image)
  {
    var rgba = (byte[])image.Rgba.Clone();
    for (int p = 0; p < rgba.Length; p += 4)
    {
      int alpha = rgba[p + 3];
      for (int c = 0; c < 3; c++)
      {
        rgba[p + c] = (byte)((rgba[p + c] * alpha + 255 * (255 - alpha) + 127) / 255);
      }

      rgba[p + 3] = 255;
    }
    using var stream = new InMemoryRandomAccessStream();
    var encoder = await BitmapEncoder.CreateAsync(BitmapEncoder.PngEncoderId, stream);
    encoder.SetPixelData(BitmapPixelFormat.Rgba8, BitmapAlphaMode.Ignore, (uint)image.Width,
        (uint)image.Height, 96, 96, rgba);
    await encoder.FlushAsync();
    stream.Seek(0);
    using var read = stream.AsStreamForRead();
    using var bytes = new MemoryStream();
    await read.CopyToAsync(bytes);
    return bytes.ToArray();
  }
  private Task Act(Func<byte[], Task> action)
  {
    var entry = Library.SelectedItem as PageEntry;
    if (entry != Tablet && entry?.Path != archivePath) return Task.CompletedTask;
    var live = entry == Tablet ? liveImage : archiveImage;
    var bytes = entry == Tablet ? null : archivePng;
    if (live == null && bytes == null) return Task.CompletedTask;
    return Act(async () => await action(bytes ?? await Encode(live!)));
  }
  private async Task Act(Func<Task> action)
  {
    if (busy) return;
    busy = true;
    Library.IsEnabled = false;
    UpdateActions();
    try
    {
      await action();
    }
    catch (Exception ex)
    {
      Feedback.Text = "작업을 완료할 수 없습니다. " + ex.Message;
    }
    finally
    {
      busy = false;
      Library.IsEnabled = true;
      UpdateActions();
    }
  }
  private async void SaveClick(object sender, RoutedEventArgs e)
  {
    if (liveImage == null)
    {
      return;
    }

    var revision = liveImage.Revision;
    await Act(async bytes =>
    {
      Directory.CreateDirectory(folder);
      var file = Path.Combine(folder, $"{DateTime.Now:yyyyMMdd-HHmmss-fff}_r{revision}_{Guid.NewGuid():N}.png");
      string temp = file + ".tmp";
      try
      {
        await File.WriteAllBytesAsync(temp, bytes);
        File.Move(temp, file);
      }
      finally
      {
        File.Delete(temp);
      }
      Library.Items.Insert(1, Entry(file));
      Feedback.Text = $"페이지 보관함에 저장했습니다 · r{revision}";
    });
  }
  private CanvasImage? CopyImage()
  {
    if (Library.SelectedItem is not PageEntry page) return null;
    var live = Live.CommittedImage;
    if (page == Tablet) return live;
    if (canvasActive && live != null && page.Id == live.Document && live.Revision >= page.Revision)
      return live;
    return archivePath == page.Path ? archiveImage : null;
  }
  internal void AddPageActions(MenuFlyout menu, Window owner)
  {
    var share = new CanvasShare(owner);
    MenuFlyoutItem Item(string title, Control source, Func<Task> action)
    {
      var item = new MenuFlyoutItem { Text = title };
      item.SetBinding(Control.IsEnabledProperty, new Microsoft.UI.Xaml.Data.Binding
      {
        Source = source,
        Path = new PropertyPath(nameof(Control.IsEnabled)),
        Mode = Microsoft.UI.Xaml.Data.BindingMode.OneWay
      });
      item.Click += async (_, _) => await action();
      return item;
    }
    menu.Items.Add(Item("복사", CopyPage, () => Copy(owner)));
    var formats = new MenuFlyoutSubItem { Text = "내보내기" };
    formats.Items.Add(Item("PNG 내보내기", ExportFormats, () => ExportPng(owner)));
    formats.Items.Add(Item("SVG 내보내기", ExportSvg, () => ExportVector(owner)));
    menu.Items.Add(formats);
    menu.Items.Add(Item("공유", SharePage, () => Share(share)));
  }

  private async void CopyClick(object sender, RoutedEventArgs e) => await Copy(window!);
  private async Task Copy(Window owner)
  {
    if (CopyImage() is not { } image) return;
    await Act(async () =>
    {
      await CanvasClipboard.Copy(WinRT.Interop.WindowNative.GetWindowHandle(owner), image);
      Feedback.Text = "그림을 클립보드에 복사했습니다.";
    });
  }
  private async void ExportClick(object sender, RoutedEventArgs e) => await ExportPng(window!);
  private Task ExportPng(Window owner) => Act(bytes => Export(owner, "PNG", stream => stream.WriteAsync(bytes).AsTask()));
  private async void ExportSvgClick(object sender, RoutedEventArgs e) => await ExportVector(window!);
  private Task ExportVector(Window owner)
  {
    var ink = archiveInk;
    return ink != null && ExportSvg.IsEnabled
      ? Act(() => Export(owner, "SVG", stream => Task.Run(() => ink.WriteSvg(stream))))
      : Task.CompletedTask;
  }
  private async Task Export(Window owner, string format, Func<Stream, Task> write)
  {
    var picker = new FileSavePicker { SuggestedFileName = $"펜 릴레이 {DateTime.Now:yyyyMMdd-HHmmss}" };
    picker.FileTypeChoices.Add(format + " 이미지", new List<string> { "." + format.ToLowerInvariant() });
    WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(owner));
    var file = await picker.PickSaveFileAsync();
    if (file == null)
    {
      return;
    }

    using var tx = await file.OpenTransactedWriteAsync();
    tx.Stream.Size = 0;
    using (var output = tx.Stream.AsStreamForWrite())
    {
      await write(output);
      await output.FlushAsync();
      await tx.CommitAsync();
    }
    Feedback.Text = format + "로 내보냈습니다 · " + file.Name;
  }

  private async void ShareClick(object sender, RoutedEventArgs e) => await Share(mainShare);
  private Task Share(CanvasShare target) => Act(async bytes =>
  {
    await target.Show(bytes);
    Feedback.Text = "공유 창에서 받을 앱을 선택하세요.";
  });
}

internal sealed class CanvasShare
{
  private readonly Window owner;
  private DataTransferManager? manager;
  private RandomAccessStreamReference? bitmap;
  private StorageFile? file;
  private bool closed;

  internal CanvasShare(Window owner)
  {
    this.owner = owner;
    owner.Closed += (_, _) =>
    {
      closed = true;
      if (manager != null) manager.DataRequested -= ShareRequested;
    };
  }
  [ComImport, Guid("3A3DCD6C-3EAB-43DC-BCDE-45671CE800C8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  private interface IDataTransferManagerInterop
  {
    IntPtr GetForWindow(IntPtr hwnd, in Guid iid);
    void ShowShareUIForWindow(IntPtr hwnd);
  }
  internal async Task Show(byte[] bytes)
  {
    if (closed) return;
    var hwnd = WinRT.Interop.WindowNative.GetWindowHandle(owner);
    var interop = DataTransferManager.As<IDataTransferManagerInterop>();
    if (manager == null)
    {
      var iid = new Guid("A5CAEE9B-8708-49D1-8D36-67D25A8DA00C");
      var abi = interop.GetForWindow(hwnd, iid);
      try
      {
        manager = WinRT.MarshalInterface<DataTransferManager>.FromAbi(abi);
      }
      finally
      {
        Marshal.Release(abi);
      }
      manager.DataRequested += ShareRequested;
    }
    var nextBitmap = RandomAccessStreamReference.CreateFromStream(bytes.AsBufferStream());
    var nextFile = await StorageFile.CreateStreamedFileAsync("펜 릴레이.png", async request =>
      {
        try
        {
          using var output = request.AsStreamForWrite();
          await output.WriteAsync(bytes);
          await output.FlushAsync();
        }
        catch
        {
          request.FailAndClose(StreamedFileFailureMode.Failed);
        }
      }, nextBitmap);
    if (closed) return;
    bitmap = nextBitmap;
    file = nextFile;
    interop.ShowShareUIForWindow(hwnd);
  }
  private void ShareRequested(DataTransferManager sender, DataRequestedEventArgs args)
  {
    args.Request.Data.Properties.Title = "펜 릴레이 페이지";
    args.Request.Data.SetBitmap(bitmap!);
    args.Request.Data.SetStorageItems(new[] { file! });
  }
}

internal static class PageStream
{
  public static IRandomAccessStream AsBufferStream(this byte[] bytes) => new MemoryStream(bytes, false).AsRandomAccessStream();
}
