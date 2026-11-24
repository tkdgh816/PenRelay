using System;
using System.Collections.Generic;
using System.Linq;

using PenRelay.Core;
using Microsoft.Graphics.Canvas;
using System.IO;
using System.Threading;
using System.Threading.Tasks;
using Windows.Storage.Streams;

using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;

using Windows.Storage;

namespace PenRelay.Desktop;

internal sealed class CanvasNavigator
{
  private sealed record Choice(Guid Id, Guid First, string Title, int Count);
  private sealed record Thumbnail(Guid Id, string Path, PageVersion Version)
  {
    internal int Width => Version.Width;
    internal int Height => Version.Height;
  }
  private readonly AppBarToggleButton noteButton = new() { Label = "노트", Icon = new SymbolIcon(Symbol.Folder) };
  private readonly AppBarToggleButton toggle = new() { Label = "페이지", Icon = new SymbolIcon(Symbol.Pictures) };
  private readonly StackPanel frames = new() { Orientation = Orientation.Horizontal, Spacing = 4, Padding = new Thickness(4) };
  private readonly ScrollViewer strip = new()
  {
    HorizontalScrollBarVisibility = ScrollBarVisibility.Auto,
    HorizontalScrollMode = ScrollMode.Enabled,
    VerticalScrollBarVisibility = ScrollBarVisibility.Disabled
  };
  private Choice[] choices = [];
  private Thumbnail[] thumbnails = [];
  private LibraryUpdate? library;
  private Guid? selected;
  private Guid? activeGroup;
  public CommandBar Commands { get; } = new()
  {
    DefaultLabelPosition = CommandBarDefaultLabelPosition.Right,
    IsDynamicOverflowEnabled = false,
    OverflowButtonVisibility = CommandBarOverflowButtonVisibility.Collapsed,
    IsEnabled = false,
    Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent),
    HorizontalAlignment = HorizontalAlignment.Left
  };
  public ScrollViewer Filmstrip => strip;
  public string PageTitle { get; private set; } = "";
  public event Action<Guid>? Requested;
  public CanvasNavigator(bool iconButtons = false)
  {
    AutomationProperties.SetName(noteButton, "노트 선택");
    AutomationProperties.SetName(toggle, "페이지 선택");
    if (iconButtons)
    {
      Commands.DefaultLabelPosition = CommandBarDefaultLabelPosition.Collapsed;
      foreach (var button in new[] { noteButton, toggle })
      {
        button.Width = 48;
        button.MinWidth = 0;
        button.Padding = new Thickness(0);
      }
    }
    Commands.PrimaryCommands.Add(noteButton);
    Commands.PrimaryCommands.Add(toggle);
    strip.Content = frames;
    strip.Style = (Style)Application.Current.Resources["FilmstripRailStyle"];
    strip.Visibility = Visibility.Collapsed;
    noteButton.Click += (_, _) =>
    {
      toggle.IsChecked = false;
      Refresh(true);
    };
    toggle.Click += (_, _) =>
    {
      noteButton.IsChecked = false;
      Refresh(true);
    };
  }
  public void Show(LibraryUpdate? data, Guid? id)
  {
    if (library?.Folder == data?.Folder && ReferenceEquals(library?.Catalog, data?.Catalog) && selected == id)
    {
      return;
    }

    bool reveal = selected != id || library?.Folder != data?.Folder;
    library = data;
    selected = id;
    Refresh(reveal);
  }
  private void Refresh(bool reveal)
  {
    var catalog = library?.Catalog;
    var groups = catalog?.PageGroups() ?? [];
    var group = groups.FirstOrDefault(g => g.Canvases.Contains(selected ?? Guid.Empty));
    Commands.IsEnabled = group != null;
    activeGroup = group?.Id;
    if (group == null)
    {
      PageTitle = "";
      strip.Visibility = Visibility.Collapsed;
      frames.Children.Clear();
      choices = [];
      thumbnails = [];
      return;
    }
    int noteIndex = Array.IndexOf(groups, group);
    string TitleFor(CanvasGroup entry, int index) =>
      catalog!.Titles?.GetValueOrDefault(entry.Canvases[0]) ?? $"노트 {index + 1}";
    PageTitle = TitleFor(group,noteIndex);
    var currentIndex = Array.IndexOf(group.Canvases, selected!.Value);
    ToolTipService.SetToolTip(noteButton, "노트 · " + PageTitle);
    ToolTipService.SetToolTip(toggle, $"페이지 {currentIndex + 1} / {group.Canvases.Length}");
    bool showNotes = noteButton.IsChecked == true;
    strip.Visibility = showNotes || toggle.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;
    if (showNotes)
    {
      var next = groups.Select((g,i) => new Choice(g.Id,g.Canvases[0],TitleFor(g,i),g.Canvases.Length)).ToArray();
      if (!choices.SequenceEqual(next))
      {
        thumbnails = [];
        frames.Children.Clear();
        choices = next;
        foreach (var note in choices) frames.Children.Add(MakeNote(note));
      }
      SelectTile(noteIndex,reveal);
      return;
    }
    if (choices.Length > 0 || toggle.IsChecked != true)
    {
      choices = [];
      frames.Children.Clear();
      thumbnails = [];
    }
    if (toggle.IsChecked != true) return;
    var versions = catalog!.Pages.ToDictionary(p => p.Id);
    var items = group.Canvases.Select(id =>
    {
      var canvas = versions[id];
      return new Thumbnail(id, PageLibrary.ImagePath(library!.Folder, canvas), canvas);
    }).ToArray();
    if (!thumbnails.SequenceEqual(items))
    {
      var previous = thumbnails.Zip(frames.Children.Cast<ToggleButton>()).ToDictionary(p => p.First, p => p.Second);
      frames.Children.Clear();
      thumbnails = items;
      for (int i = 0; i < items.Length; i++)
      {
        var item = items[i];
        var tile = previous.GetValueOrDefault(item) ?? MakeTile(item);
        AutomationProperties.SetName(tile, $"페이지 {i + 1}");
        frames.Children.Add(tile);
      }
    }
    SelectTile(currentIndex,reveal);
  }
  private ToggleButton MakeNote(Choice note)
  {
    var template = (DataTemplate)Application.Current.Resources["NoteCardContent"];
    var card = (Grid)template.LoadContent();
    ((TextBlock)card.FindName("NoteCount")).Text = note.Count.ToString();
    ((TextBlock)card.FindName("NoteTitle")).Text = note.Title;
    var tile = new ToggleButton
    {
      Style = (Style)Application.Current.Resources["FilmstripThumbnailStyle"],
      Width = 100,
      Content = card
    };
    AutomationProperties.SetName(tile, $"{note.Title}, 페이지 {note.Count}개");
    ToolTipService.SetToolTip(tile, note.Title);
    tile.Click += (_, _) =>
    {
      noteButton.IsChecked = false;
      toggle.IsChecked = true;
      if (note.Id != activeGroup) Requested?.Invoke(note.First);
      Refresh(true);
    };
    return tile;
  }
  private void SelectTile(int currentIndex, bool reveal)
  {
    for (int i = 0; i < frames.Children.Count; i++)
    {
      ((ToggleButton)frames.Children[i]).IsChecked = i == currentIndex;
    }

    if (reveal)
    {
      var tile = (ToggleButton)frames.Children[currentIndex];
      strip.DispatcherQueue.TryEnqueue(() =>
      {
        if (strip.Visibility == Visibility.Visible && tile.IsChecked == true && tile.Parent == frames)
        {
          tile.StartBringIntoView();
        }
      });
    }
  }
  private static async Task<IRandomAccessStream> ThumbnailStream(Thumbnail item, CancellationToken token)
  {
    if (item.Version.ArchiveKey == null)
    {
      var file = await StorageFile.GetFileFromPathAsync(item.Path);
      return await file.OpenReadAsync();
    }
    return await Task.Run(async () =>
    {
      var ink = PageLibrary.ReadStoredInk(Path.GetDirectoryName(item.Path)!,item.Version, token, forThumbnail: true);
      var scale = Math.Min(1, Math.Min(160f / ink.Width,120f / ink.Height));
      using var bitmap = InkDrawing.Render(CanvasDevice.GetSharedDevice(true),ink,96 * scale,token);
      var stream = new InMemoryRandomAccessStream();
      try
      {
        await bitmap.SaveAsync(stream,CanvasBitmapFileFormat.Png);
        stream.Seek(0);
        return stream;
      }
      catch { stream.Dispose(); throw; }
    });
  }

  private ToggleButton MakeTile(Thumbnail item)
  {
    double tileHeight = (double)Application.Current.Resources["FilmstripTileHeight"];
    var scale = Math.Min(92.0 / item.Width, (tileHeight - 4) / item.Height);
    var picture = new Image { Stretch = Stretch.Uniform, Width = item.Width * scale, Height = item.Height * scale };
    var tile = new ToggleButton
    {
      Style = (Style)Application.Current.Resources["FilmstripThumbnailStyle"],
      Content = new Border
      {
        HorizontalAlignment = HorizontalAlignment.Center,
        VerticalAlignment = VerticalAlignment.Center,
        Background = new SolidColorBrush(Microsoft.UI.Colors.White),
        Child = picture
      }
    };
    tile.Click += (_, _) =>
    {
      tile.IsChecked = true;
      if (selected != item.Id)
      {
        Requested?.Invoke(item.Id);
      }
    };
    CancellationTokenSource? loading = null;
    void Cancel()
    {
      loading?.Cancel();
      loading = null;
    }
    tile.Unloaded += (_, _) => Cancel();
    tile.EffectiveViewportChanged += async (_, args) =>
    {
      if (args.BringIntoViewDistanceX > 120 || args.EffectiveViewport.Width <= 0)
      {
        Cancel();
        return;
      }
      if (loading != null || picture.Source != null) return;
      using var request = new CancellationTokenSource();
      loading = request;
      try
      {
        using var stream = await ThumbnailStream(item, request.Token);
        request.Token.ThrowIfCancellationRequested();
        var bitmap = new BitmapImage();
        if (item.Width >= item.Height)
        {
          bitmap.DecodePixelWidth = 160;
        }
        else
        {
          bitmap.DecodePixelHeight = 120;
        }

        await bitmap.SetSourceAsync(stream);
        if (!request.IsCancellationRequested) picture.Source = bitmap;
      }
      catch (Exception) { /* 다음 표시 시 다시 요청한다. */ }
      finally { if (loading == request) loading = null; }
    };
    return tile;
  }
}
