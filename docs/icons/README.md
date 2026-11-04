# HD television tiles

Seven custom tvOS-inspired 16:9 PNG tiles were generated with the built-in image_gen tool.
The exact generation prompts are in [prompts.json](prompts.json); final assets live in
`app/src/main/assets/icons-hd/`. These are custom interpretations for this launcher, not official
artwork supplied by the app publishers.

| Asset | Application packages |
|---|---|
| tencent.png | com.ktcp.video |
| netease.png | com.netease.cloudmusic.tv |
| karaoke.png | com.tencent.karaoketv |
| weather.png | com.xiaomi.tweather |
| gallery.png | com.mitv.gallery |
| fashion.png | com.xiaomi.tv.gallery |
| player.png | com.xiaomi.mitv.mediaexplorer |

The original generated files are retained without resampling. Version 52 fixes PNG decoder
subsampling aliasing: load the original on the catalog worker, progressively halve with bilinear
filtering, and retain at most twice the tile width. Temporary bitmaps are recycled. The resting
and focused faces also prefilter large reductions once; frame drawing explicitly uses filtering.
The versioned disk cache avoids repeated work. User-uploaded icons still take precedence.

Each bundled asset maps to one package. Fashion wallpaper now has framed artwork, distinct from
the photo gallery flower. The internal video playback service no longer borrows the media browser
icon. Xiaomi TV settings uses blue adjustment switches, distinct from Android settings' gear.
TileSheet now scales the full artwork instead of cropping the upper-left portion of HD assets.
