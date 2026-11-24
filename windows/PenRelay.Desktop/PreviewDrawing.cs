using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;

using PenRelay.Core;
using Microsoft.Graphics.Canvas;
using Microsoft.Graphics.Canvas.Geometry;

namespace PenRelay.Desktop;

// 창마다 기기에 맞는 질감과 완성된 윤곽 조각을 재사용한다.
internal sealed class PreviewDrawing : IDisposable
{
  private readonly Dictionary<InkTexture, CanvasBitmap> tiles = new();
  private readonly Dictionary<Guid, StrokeGeometry> strokes = new();
  private bool disposed;

  public async Task Load(CanvasDevice device)
  {
    foreach (var texture in new[] { InkTexture.Ballpoint, InkTexture.Felt, InkTexture.Graphite })
    {
      var tile = await InkDrawing.LoadTile(device, texture);
      if (disposed) { tile.Dispose(); return; }
      tiles.Add(texture, tile);
    }
  }

  public void Draw(CanvasDrawingSession session, IEnumerable<PreviewLine> lines)
  {
    foreach (var line in lines)
    {
      if (!strokes.TryGetValue(line.Id, out var cached) || !ReferenceEquals(cached.Style, line.Style))
      {
        cached?.Dispose();
        strokes[line.Id] = cached = new(line.Style);
      }
      if (line.Style.Brush == 4)
      {
        cached.DrawTiles(session, line);
        continue;
      }
      tiles.TryGetValue(line.Style.Texture, out var tile);
      InkDrawing.Paint(session, line.Style, tile, (width, color) =>
        cached.Draw(session, line, width, color));
    }
    foreach (var id in strokes.Keys.Where(id => !lines.Any(line => line.Id == id)).ToArray())
    {
      strokes[id].Dispose();
      strokes.Remove(id);
    }
  }

  private sealed class StrokeGeometry(InkStroke style) : IDisposable
  {
    public InkStroke Style { get; } = style;
    private readonly Dictionary<float, List<CanvasGeometry>> layers = new();
    private readonly Dictionary<int, (EraseTile Tile, CanvasBitmap Bitmap)> eraseTiles = new();

    public void DrawTiles(CanvasDrawingSession session, PreviewLine line)
    {
      foreach (var (key, tile) in line.Tiles)
      {
        if (!eraseTiles.TryGetValue(key, out var cached) || !ReferenceEquals(cached.Tile, tile))
        {
          cached.Bitmap?.Dispose();
          cached = (tile, CanvasBitmap.CreateFromBytes(session.Device, tile.Bgra, tile.Width, tile.Height,
            global::Windows.Graphics.DirectX.DirectXPixelFormat.B8G8R8A8UIntNormalized, 96f));
          eraseTiles[key] = cached;
        }
        session.DrawImage(cached.Bitmap, tile.X, tile.Y);
      }
    }

    public void Draw(CanvasDrawingSession session, PreviewLine line, float width, global::Windows.UI.Color color)
    {
      if (!layers.TryGetValue(width, out var blocks)) layers[width] = blocks = new();
      const int batch = InkDrawing.GeometryBatch;
      for (int first = 0; first < line.Points.Count; first += batch)
      {
        int index = first / batch;
        if (index < blocks.Count)
        {
          session.FillGeometry(blocks[index], color);
          continue;
        }
        int count = Math.Min(batch, line.Points.Count - first);
        int overlap = first == 0 ? 0 : 1;
        var samples = new InkSample[overlap + count];
        line.Points.CopyTo(first - overlap, samples, 0, samples.Length);
        var stroke = Style with { Width = width, Points = samples };
        var geometry = InkDrawing.Outline(session.Device, stroke, overlap, samples.Length);
        if (count == batch)
        {
          blocks.Add(geometry);
          session.FillGeometry(geometry, color);
        }
        else
        {
          using (geometry) session.FillGeometry(geometry, color);
        }
      }
    }

    public void Dispose()
    {
      foreach (var tile in eraseTiles.Values) tile.Bitmap.Dispose();
      foreach (var blocks in layers.Values)
        foreach (var geometry in blocks) geometry.Dispose();
    }
  }

  public void Dispose()
  {
    disposed = true;
    foreach (var stroke in strokes.Values) stroke.Dispose();
    foreach (var tile in tiles.Values) tile.Dispose();
    strokes.Clear();
    tiles.Clear();
  }
}
