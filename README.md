# Spine Skeleton Exporter

Export spine skeleton to PNG/MP4.

## Usage

```bash
$ java -jar skeletonexporter.jar \
    -i /path/to/spine/skeleton.json \
        # input spin skeleton json file or directory
    -d /path/to/output/directory \
        # output directory
    -o '%2$04d_%3$02d_%1$s.mp4' \ 
        # output file pattern
        # %1$s is input filename without '.json', string
        # %2$04d is skeleton index, integer
        # %3$02d is animation index, integer
        # see https://docs.oracle.com/javase/7/docs/api/java/util/Formatter.html for reference
    --ffmpeg /path/to/ffmpeg \
        # path to the ffmpeg binary
    -c:v libx264 -crf 22 -pix_fmt yuv420p
        # ffmpeg output options, default values shown here
```

## Notes on camera positioning

The method
```java
public static ViewportMethod computeViewport(Skeleton skeleton, float[] whxy)
```
in `io.github.ssz66666.skeletonexporter.SkeletonOutputRenderer`

includes several methods for correctly zooming and positioning the camera for each skeleton:

- hard-coded width/height/x/y for specific skeletons
- positioning using the background image slot
- positioning using AABB(axis-aligned bounding box)

Try to find the name of the background image slot (usually "BG" or "bg") and use method 2.
Some skeletons use non-standard name like 'haikei', which stands for '背景', 'background' in Japanese.

If method 2 doesn't work see if the fallback method 3 produces a satisfactory result.

If not you need to hard-code the width/height/x/y for your skeleton.

## Build

macOS/Linux

```
$ ./gradlew desktop:dist
```

Windows

```
$ .\gradlew.bat desktop:dist
```

generated jar is at `$PROJECT_DIR/desktop/build/libs/desktop-1.0.jar`
