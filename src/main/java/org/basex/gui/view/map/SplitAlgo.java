package org.basex.gui.view.map;

/**
 * SplitLayout algorithm.
 *
 * @author BaseX Team 2005-15, BSD License
 * @author Joerg Hauser
 */
final class SplitAlgo extends MapAlgo {
  @Override
  MapRects calcMap(final MapRect r, final MapList ml, final int ns, final int ne) {
    return calcMap(r, ml, ns, ne, 1);
  }

  /**
   * Uses recursive SplitLayout algorithm to divide rectangles on one level.
   * @param r parent rectangle
   * @param ml children array
   * @param ns start array position
   * @param ne end array position
   * @param sw weight of this recursion level
   * @return rectangles
   */
  private static MapRects calcMap(final MapRect r, final MapList ml, final int ns, final int ne,
      final double sw) {

    if(ne - ns == 0) {
      final MapRects rects = new MapRects();
      rects.add(new MapRect(r, ml.get(ns)));
      return rects;
    }

    final MapRects rects = new MapRects();
    int ni = ns - 1;

    // increment pivot until left rectangle contains more or equal
    // than half the weight or leave with just setting it to ne - 1
    double w = 0;
    while(ni < ne) {
      w += ml.weight[++ni];
      if(w >= sw / 2 || ni == ne - 1) break;
    }

    int xx = r.x;
    int yy = r.y;
    int ww = r.w > r.h ? (int) (r.w / sw * w) : r.w;
    int hh = r.w > r.h ? r.h : (int) (r.h / sw * w);
    // paint both rectangles if enough space is left
    if(ww > 0 && hh > 0 && w > 0) rects.add(calcMap(
        new MapRect(xx, yy, ww, hh, 0, r.level), ml, ns, ni, w));
    if(r.w > r.h) {
      xx += ww;
      ww = r.w - ww;
    } else {
      yy += hh;
      hh = r.h - hh;
    }

    if(ww > 0 && hh > 0 && sw - w > 0 && ni + 1 <= ne)
      rects.add(calcMap(new MapRect(xx, yy, ww, hh, 0, r.level), ml, ni + 1, ne, sw - w));

    return rects;
  }
}
