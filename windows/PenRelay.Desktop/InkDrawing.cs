using System.Numerics;
using System;
using System.Collections.Generic;
using System.IO;
using System.Threading;
using System.Threading.Tasks;

using PenRelay.Core;

using Microsoft.Graphics.Canvas;
using Microsoft.Graphics.Canvas.Brushes;
using Microsoft.Graphics.Canvas.Geometry;

using Windows.UI;

namespace PenRelay.Desktop;

internal static class InkDrawing
{
  internal static PenRelay.Core.CanvasImage Rasterize(PageVersion version, InkPage ink, CancellationToken token)
  {
    var device = CanvasDevice.GetSharedDevice(true);
    using var bitmap = Render(device,ink,96,token);
    var rgba = bitmap.GetPixelBytes();
    for (int i = 0; i < rgba.Length; i += 4) (rgba[i],rgba[i+2]) = (rgba[i+2],rgba[i]);
    return new(version.Id,version.Revision,ink.Width,ink.Height,rgba,"");
  }

  public static CanvasBitmap Render(CanvasDevice display, InkPage page, float dpi, CancellationToken token)
  {
    // 확대 표면의 곡선 분할과 채우기가 창의 Direct2D 기기를 잠그지 않게 한다.
    var worker = CanvasDevice.GetSharedDevice(true);
    using var surface = new CanvasRenderTarget(worker, page.Width, page.Height, dpi);
    using (var session = surface.CreateDrawingSession())
    {
      session.Clear(Microsoft.UI.Colors.White);
      var tiles = new Dictionary<InkTexture, CanvasBitmap>();
      try
      {
        foreach (var stroke in page.Strokes)
        {
          token.ThrowIfCancellationRequested();
          CanvasBitmap? tile = null;
          if (stroke.Texture != InkTexture.Smooth && !tiles.TryGetValue(stroke.Texture, out tile))
          {
            tile = LoadTile(worker, stroke.Texture).GetAwaiter().GetResult();
            tiles.Add(stroke.Texture, tile);
          }
          Paint(session, stroke, tile, (width, color) =>
          {
            var ink = stroke with { Width = width };
            for (int first = 0; first < ink.Points.Length; first += GeometryBatch)
            {
              token.ThrowIfCancellationRequested();
              using var geometry = Outline(worker, ink, first, first + GeometryBatch);
              session.FillGeometry(geometry, color);
            }
          });
        }
      }
      finally
      {
        foreach (var tile in tiles.Values) tile.Dispose();
      }
    }
    token.ThrowIfCancellationRequested();
    var pixels = surface.GetPixelBytes();
    var size = surface.SizeInPixels;
    return CanvasBitmap.CreateFromBytes(display, pixels, (int)size.Width, (int)size.Height,
        Windows.Graphics.DirectX.DirectXPixelFormat.B8G8R8A8UIntNormalized, dpi, CanvasAlphaMode.Premultiplied);
  }
  internal const int GeometryBatch = 32;

  internal static async Task<CanvasBitmap> LoadTile(CanvasDevice device, InkTexture texture)
  {
    using var source = InkStroke.OpenTile(texture);
    using var stream = source.AsRandomAccessStream();
    return await CanvasBitmap.LoadAsync(device, stream, 96f).AsTask().ConfigureAwait(false);
  }

  internal static void Paint(CanvasDrawingSession session, InkStroke stroke, CanvasBitmap? tile, Action<float, Color> fill)
  {
    uint rgb = stroke.Brush == 3 ? 0xffffff : stroke.Color;
    var color = Color.FromArgb(255, (byte)(rgb >> 16), (byte)(rgb >> 8), (byte)rgb);
    // 반투명도는 모든 배치를 합친 한 획에 한 번만 적용한다.
    using var layer = stroke.Alpha == 255 ? null : session.CreateLayer(stroke.Alpha / 255f);
    if (tile == null)
    {
      fill(stroke.Width, color);
      return;
    }
    using var mask = new CanvasImageBrush(session.Device, tile) {
      ExtendX = CanvasEdgeBehavior.Wrap, ExtendY = CanvasEdgeBehavior.Wrap,
      Transform = Matrix3x2.CreateScale(stroke.TileSize / (float)tile.Size.Width)
    };
    using (session.CreateLayer(mask)) fill(stroke.Width, color);
    if (stroke.Texture == InkTexture.Felt) fill(stroke.Width * .76f, color);
  }

  internal static CanvasGeometry Outline(CanvasDevice device, InkStroke stroke, int start, int end)
  {
    using var path = new CanvasPathBuilder(device);
    path.SetFilledRegionDetermination(CanvasFilledRegionDetermination.Winding);
    stroke.Outline((p, r) => Circle(path, p, r), (a, b, c, d) =>
    {
      path.BeginFigure(a);
      path.AddLine(b);
      path.AddLine(c);
      path.AddLine(d);
      path.EndFigure(CanvasFigureLoop.Closed);
    }, start, end);
    return CanvasGeometry.CreatePath(path);
  }

  private static void Circle(CanvasPathBuilder path, Vector2 p, float r)
  {
    var x = new Vector2(r, 0);
    var y = new Vector2(0, r);
    var cx = x * .55228475f;
    var cy = y * .55228475f;
    path.BeginFigure(p + x);
    path.AddCubicBezier(p + x + cy, p + y + cx, p + y);
    path.AddCubicBezier(p + y - cx, p - x + cy, p - x);
    path.AddCubicBezier(p - x - cy, p - y - cx, p - y);
    path.AddCubicBezier(p - y + cx, p + x - cy, p + x);
    path.EndFigure(CanvasFigureLoop.Closed);
  }
}
