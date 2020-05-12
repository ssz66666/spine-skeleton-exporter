package io.github.ssz66666.skeletonexporter;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.Thread.UncaughtExceptionHandler;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.zip.Deflater;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Pixmap.Format;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.graphics.TextureData;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.graphics.g2d.TextureAtlas.AtlasRegion;
import com.badlogic.gdx.graphics.g2d.TextureAtlas.TextureAtlasData;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.ScreenUtils;
import com.esotericsoftware.spine.Animation;
import com.esotericsoftware.spine.AnimationState;
import com.esotericsoftware.spine.AnimationStateData;
import com.esotericsoftware.spine.Bone;
import com.esotericsoftware.spine.Skeleton;
import com.esotericsoftware.spine.SkeletonData;
import com.esotericsoftware.spine.SkeletonJson;
import com.esotericsoftware.spine.SkeletonRenderer;
import com.esotericsoftware.spine.Slot;
import com.esotericsoftware.spine.AnimationState.TrackEntry;
import com.esotericsoftware.spine.attachments.AtlasAttachmentLoader;
import com.esotericsoftware.spine.attachments.Attachment;
import com.esotericsoftware.spine.attachments.MeshAttachment;
import com.esotericsoftware.spine.attachments.RegionAttachment;
import com.esotericsoftware.spine.utils.TwoColorPolygonBatch;

public class SkeletonOutputRenderer extends ApplicationAdapter {

	private static class Frame {
		public enum Type {
			Image, Frame, Picture, EOF
		}

		public final Pixmap data;
		public final Frame.Type type;
		public final int skeletonIndex;
		public final int animationIndex;
		public final int frameIndex;

		public Frame(Pixmap b, Frame.Type t, int s, int a, int f) {
			this.data = b;
			this.type = t;
			this.skeletonIndex = s;
			this.animationIndex = a;
			this.frameIndex = f;
		}

		public static final Frame EOFFrame = new Frame(null, Type.EOF, 0, 0, 0);
	}
	
	// format is always RGBA8888
//	private static class PixmapPool {
//		private ConcurrentHashMap<String, ConcurrentLinkedQueue<Pixmap>> pools = new ConcurrentHashMap<>();
//		
//		public static String dimensionsToKey(int width, int height) {
//			return String.format("%dx%d", width, height);
//		}
//		
//		public Pixmap borrowPixmap(int width, int height) {
//			String key = PixmapPool.dimensionsToKey(width, height);
//			ConcurrentLinkedQueue<Pixmap> pool = pools.get(key);
//			if (pool == null) {
//				ConcurrentLinkedQueue<Pixmap> newPool = new ConcurrentLinkedQueue<Pixmap>();
//				pool = pools.putIfAbsent(key, newPool);
//				if (pool == null) {
//					pool = newPool;
//				}
//			}
//			Pixmap pm = pool.poll();
//			while (pm == null) {
//				pool.add(new Pixmap(width, height, Format.RGBA8888));
//				pm = pool.poll();
//			}
//			return pm;
//		}
//		
//		public void returnPixmap(Pixmap pm) {
//			String key = PixmapPool.dimensionsToKey(pm.getWidth(), pm.getHeight());
//			ConcurrentLinkedQueue<Pixmap> pool = pools.get(key);
//			pool.add(pm);
//		}
//	}
	
	private Pixmap getFrameBufferAsPixmap (int x, int y, int w, int h) {
		Gdx.gl.glPixelStorei(GL20.GL_PACK_ALIGNMENT, 1);

//		final Pixmap pixmap = pixmapBufferPool.borrowPixmap(w, h);
		final Pixmap pixmap = new Pixmap(w, h, Format.RGBA8888);
		ByteBuffer pixels = pixmap.getPixels();
		Gdx.gl.glReadPixels(x, y, w, h, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, pixels);

		return pixmap;
	}

	private final Path inputPath;
	private final String outputPattern;
	private final String ffmpegBinary;
	private final List<String> ffmpegArgs;

	private Skeleton skeleton;
	private final List<Object> loadedAtlas = new ArrayList<>();
	private final Map<Texture, Pixmap> tmpTextures = new HashMap<>();
	private AnimationState state;
	private Array<Animation> animations;
	private TwoColorPolygonBatch batch;
	private SkeletonRenderer renderer;
	private OrthographicCamera camera;
	private OrthographicCamera screenCamera;
	private FrameBuffer fbo;

	private List<FileHandle> skeletonFiles;
	
//	private PixmapPool pixmapBufferPool = new PixmapPool();

	protected boolean paused = false;
	
	private int width = 0;
	private int height = 0;

	private boolean isFirst = true;
	private boolean isDone = false;
	private boolean isFirstFrame = true;
	private int currentSkeletonIndex = 0;
	private int currentAnimationIndex = 0;
	private int currentFrameIndex = 0;
	private Frame[] frameBlocked = new Frame[2];

	private boolean noImage = false;
	private boolean noAnimation = false;
	private boolean doRecalibrate = false;

	private int workers = 0;
	private Object workerLock = new Object();

	public final int LIBX264_CRF = 23;

	public enum ViewportMethod {
		Constant, Background, AABB
	}

	private LinkedBlockingQueue<Frame> imagesToWrite = new LinkedBlockingQueue<>(5);
	private LinkedBlockingQueue<Frame> framesToEncode = new LinkedBlockingQueue<>(30);
//	private LinkedBlockingQueue<Frame> framesToMux = new LinkedBlockingQueue<>(60);

	public static Object[] getSkeleton(final FileHandle skeletonFile) {
		// Setup a texture atlas that uses a white image for images not found in the
		// atlas.
//		Pixmap pixmap = new Pixmap(32, 32, Format.RGBA8888);
//		pixmap.setColor(new Color(1, 1, 1, 0.33f));
//		pixmap.fill();
//		final AtlasRegion fake = new AtlasRegion(new Texture(pixmap), 0, 0, 32, 32);
//		pixmap.dispose();
		
		FileHandle atlasFile = skeletonFile.sibling(skeletonFile.nameWithoutExtension() + ".atlas");
		TextureAtlasData data = !atlasFile.exists() ? null : new TextureAtlasData(atlasFile, atlasFile.parent(), false);

		TextureAtlas atlas = new TextureAtlas(data);
		for (Texture texture : atlas.getTextures())
			texture.setFilter(TextureFilter.Linear, TextureFilter.Linear);

		SkeletonJsonOld json = new SkeletonJsonOld(atlas);
		SkeletonData skeletonData = json.readSkeletonData(skeletonFile);
		return new Object[] {new Skeleton(skeletonData), atlas};
	}

	public SkeletonOutputRenderer(Path inputPath, Path outputPath, String outputPattern, String ffmpegBinary,
			List<String> ffmpegArgs) {
		this.inputPath = inputPath;
		String p = outputPath.resolve("a").toAbsolutePath().toString();
		this.outputPattern = p.substring(0, p.length() - 1) + outputPattern;
		this.ffmpegBinary = ffmpegBinary;
		this.ffmpegArgs = ffmpegArgs;

		System.err.println(inputPath);
		System.err.println(this.outputPattern);
		System.err.println(this.ffmpegBinary);
		System.err.println(this.ffmpegArgs);
	}

	@Override
	public void create() {
		Thread.setDefaultUncaughtExceptionHandler(new UncaughtExceptionHandler() {
			public void uncaughtException(Thread thread, Throwable ex) {
				ex.printStackTrace();
				Runtime.getRuntime().halt(0); // Prevent Swing from keeping JVM alive.
			}
		});
//		Gdx.graphics.setContinuousRendering(false);
		batch = new TwoColorPolygonBatch(32767);
		renderer = new SkeletonRenderer();
		camera = new OrthographicCamera();
		screenCamera = new OrthographicCamera();

		skeletonFiles = new ArrayList<>();

		if (inputPath.toFile().isFile()) {
			skeletonFiles.add(new FileHandle(inputPath.toFile()));
		} else {
			try {
				Files.walkFileTree(this.inputPath, new SimpleFileVisitor<Path>() {
					@Override
					public FileVisitResult visitFile(Path p, BasicFileAttributes bfa) throws IOException {
						if (p.getFileName().toString().endsWith(".json")) {
							skeletonFiles.add(new FileHandle(p.toFile()));
						}
						return FileVisitResult.CONTINUE;
					}
				});
			} catch (IOException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}
			Collections.sort(skeletonFiles, (new Comparator<FileHandle>() {
				@Override
				public int compare(FileHandle f1, FileHandle f2) {
					return f1.nameWithoutExtension().compareToIgnoreCase(f2.nameWithoutExtension());
				}

			}));
		}

//		
//		skeletonFiles = skeletonFiles.subList(0, 397);
//		currentSkeletonIndex = 614;

//		skeletonFiles.clear();
//		skeletonFiles.add(new FileHandle("/home/ssz/Downloads/other/otogi/still/abel_S1.json"));
//		skeletonFiles.add(new FileHandle("/home/ssz/Downloads/other/otogi/still/meloss_S2.json"));
//		skeletonFiles.add(new FileHandle("/home/ssz/Downloads/other/otogi/still/hook_1.json"));
//		skeletonFiles.add(new FileHandle("/home/ssz/Downloads/other/otogi/still/allpower_0.json"));
//		skeletonFiles.add(new FileHandle("/home/ssz/Downloads/other/otogi/still/kame_2_180518.json"));
//		skeletonFiles.add(new FileHandle("/home/ssz/Downloads/other/otogi/still/daikokuten_2.json"));

		loadSkeleton(skeletonFiles.get(currentSkeletonIndex));
		

		setAnimation(animations.get(currentAnimationIndex));
		Thread imgWriter = new Thread(new ImageWriter(imagesToWrite));
//		Thread imgWriter = new Thread(new NoopFrameMuxer(imagesToWrite));
		imgWriter.start();
		Thread ffmpegMuxer = new Thread(new FFMPEGFrameMuxer(framesToEncode, ffmpegBinary, ffmpegArgs));
//		Thread ffmpegMuxer = new Thread(new NoopFrameMuxer(framesToEncode));
		ffmpegMuxer.start();
	}

	private class ImageWriter implements Runnable {
		final LinkedBlockingQueue<Frame> q;
		final String imgOutputPattern;

		public ImageWriter(LinkedBlockingQueue<Frame> q) {
			this.q = q;
			this.imgOutputPattern = outputPattern.substring(0, outputPattern.lastIndexOf(".")) + ".png";
		}

		@Override
		public void run() {
			synchronized (workerLock) {
				workers += 1;
			}
			Frame frm = null;
			try {
				frm = q.take();
				while (!frm.type.equals(Frame.Type.EOF)) {

					String skeletonName = skeletonFiles.get(frm.skeletonIndex).nameWithoutExtension();
					PixmapIO.writePNG(new FileHandle(
							String.format(imgOutputPattern, skeletonName, frm.skeletonIndex, frm.animationIndex)),
							frm.data,
							Deflater.DEFAULT_COMPRESSION,
							true);
//					pixmapBufferPool.returnPixmap(frm.data);
					frm.data.dispose();
					frm = q.take();
				}
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} finally {
				synchronized (workerLock) {
					workers -= 1;
					if (workers == 0) {
						Gdx.app.exit();
					} else {
						q.offer(Frame.EOFFrame);
					}
				}
			}
		}
	}

	public static class InputStreamDrain implements Runnable {

		private final InputStream in;

		public InputStreamDrain(InputStream in) {
			this.in = in;
		}

		@Override
		public void run() {
			try {
				while (in.read() != -1) {
				}
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}

	public class FFMPEGFrameMuxer implements Runnable {

		final LinkedBlockingQueue<Frame> q;
		final ProcessBuilder pb;
		final int FRAMERATE_INDEX = 4;
		final int SIZE_INDEX = 6;
		Process p = null;
		WritableByteChannel stdin = null;

		public FFMPEGFrameMuxer(LinkedBlockingQueue<Frame> in, String ffmpeg, List<String> ffmpegArgs) {
			this.q = in;
			this.pb = new ProcessBuilder(ffmpeg, "-f", "rawvideo", "-framerate", "30", "-video_size", "SIZE",
					"-pixel_format", "rgba", "-i", "pipe:0", "-vf", "vflip", "-y");
			pb.command().addAll(ffmpegArgs);
			pb.command().add("/dev/null"); // placeholder for output path
		}

		@Override
		public void run() {
			synchronized (workerLock) {
				workers += 1;
			}

			Frame frm = null;
			int encodingSkeleton = -1;
			int encodingAnimation = -1;

			try {
				frm = q.take();
				while (!frm.type.equals(Frame.Type.EOF)) {

					String skeletonName = skeletonFiles.get(frm.skeletonIndex).nameWithoutExtension();

					if (frm.animationIndex != encodingAnimation || frm.skeletonIndex != encodingSkeleton) {
						if (p != null) {
							stdin.close();
							stdin = null;
							p = null;
						}

						encodingSkeleton = frm.skeletonIndex;
						encodingAnimation = frm.animationIndex;

						List<String> args = pb.command();
						String outputPath = String.format(outputPattern, skeletonName, frm.skeletonIndex,
								frm.animationIndex + 1);
						args.set(SIZE_INDEX, String.format("%dx%d", frm.data.getWidth(), frm.data.getHeight()));
						args.set(args.size() - 1, outputPath);
						p = pb.start();
						stdin = Channels.newChannel(p.getOutputStream());
						new Thread(new InputStreamDrain(p.getInputStream())).start();
						new Thread(new InputStreamDrain(p.getErrorStream())).start();
					}
					stdin.write(frm.data.getPixels());
//					pixmapBufferPool.returnPixmap(frm.data);
					frm.data.dispose();
					frm = q.take();
				}

//				
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} finally {
				if (stdin != null) {
					try {
						stdin.close();
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
				if (p != null) {
					try {
						p.waitFor();
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
				synchronized (workerLock) {
					workers -= 1;
					if (workers == 0) {
						Gdx.app.exit();
					} else {
						q.offer(Frame.EOFFrame);
					}
				}
			}
		}

	}

	public class NoopFrameMuxer implements Runnable {

		final LinkedBlockingQueue<Frame> q;

		public NoopFrameMuxer(LinkedBlockingQueue<Frame> q) {
			this.q = q;
		}

		@Override
		public void run() {
			synchronized (workerLock) {
				workers += 1;
			}

			Frame frm = null;
			try {
				frm = q.take();

				while (!frm.type.equals(Frame.Type.EOF)) {
					String skeletonName = skeletonFiles.get(frm.skeletonIndex).nameWithoutExtension();
					System.err.println(String.format("SkeletonIndex: %04d", frm.skeletonIndex));
					System.err.println(String.format("SkeletonName: %s", skeletonName));
					System.err.println(String.format("AnimationIndex: %d", frm.animationIndex));
					System.err.println(String.format("FrameIndex: %04d", frm.frameIndex));
//					pixmapBufferPool.returnPixmap(frm.data);
					frm.data.dispose();
					frm = q.take();
				}
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} finally {
				synchronized (workerLock) {
					workers -= 1;
					if (workers == 0) {
						Gdx.app.exit();
					} else {
						q.offer(Frame.EOFFrame);
					}
				}
			}

		}

	}

	// specific to Otogi Frontier R
	public static ViewportMethod computeViewport(Skeleton skeleton, float[] whxy) {
		float fw, fh, x, y;
		SkeletonData skeletonData = skeleton.getData();

		ViewportMethod method = ViewportMethod.Constant;

		// special case for moving background: meloss_S2
		if (skeletonData.getName().equals("meloss_S2")) {
			fw = 1302f;
			fh = 695f;
			x = 1.37f;
			y = 456.5f;
		} else
		// iAmCat_1
		if (skeletonData.getName().equals("iAmCat_1")) {
			System.err.println("iAmCat_1");
			x = -12.0f;
			y = 430.0f;
			fw = 1368.0f;
			fh = 850.0f;
		} else if (skeletonData.getName().equals("king_12")) {
			System.err.println("king_12");
			x = 0;
			y = 450;
			fw = 1402;
			fh = 898;
		} else if (skeletonData.getName().equals("maura_schill1")) {
			System.err.println("maura_schill1");
			x = 40;
			y = 39.4f;
			fw = 1201;
			fh = 769;
		} else if (skeletonData.getName().equals("aladin_1")) {
			System.err.println("aladin_1");
			x = 14f;
			y = 322f;
			fw = 1120;
			fh = 640;
		} else if (skeletonData.getName().equals("jiminyCricket_1")) {
			System.err.println("jiminyCricket_1");
			x = -12f;
			y = 374f;
			fw = 1144;
			fh = 746;
		} else if (skeletonData.getName().equals("redhood_1")) {
			System.err.println("redhood_1");
			x = 0f;
			y = 409f;
			fw = 1144;
			fh = 746;
		} else if (skeletonData.getName().equals("redhood_2")) {
			System.err.println("redhood_2");
			x = -16f;
			y = 388f;
			fw = 1144;
			fh = 746;
		} else if (skeletonData.getName().equals("yuchong")) {
			System.err.println("yuchong");
			x = 44f;
			y = 455f;
			fw = 1120;
			fh = 640;
		} else if (skeletonData.getName().equals("lady")) {
			System.err.println("lady");
			x = 28f;
			y = 453f;
			fw = 1120;
			fh = 640;
		} else {
			Bone bone = null;
			Attachment att = null;
			Slot slot = null;
			method = ViewportMethod.Background;
			// darothy2ndAnniversary_S2
			if (skeletonData.getName().equals("dorothy2ndAnniversary_S2")) {
				System.err.println("dorothy2ndAnniversary_S2");
				slot = skeleton.findSlot("shadow");
				bone = slot.getBone();
				att = slot.getAttachment();
			} else
			// daikokuten_1
			if (skeletonData.getName().equals("daikokuten_1")) {
				System.err.println("daikokuten_1");
				slot = skeleton.findSlot("bg_Mirror");
				bone = slot.getBone();
				att = slot.getAttachment();
			} else // yamanba
			if (skeletonData.getName().equals("yamanba")) {
				System.err.println("yamanba");
				slot = skeleton.findSlot("BG 1");
				bone = slot.getBone();
				att = slot.getAttachment();
			} else // anne_2
			if (skeletonData.getName().equals("anne_2")) {
				System.err.println("anne_2");
				slot = skeleton.findSlot("BG2");
				bone = slot.getBone();
				att = slot.getAttachment();
			} else // Cinderella_scean_01
			if (skeletonData.getName().equals("Cinderella_scean_01")) {
				System.err.println("Cinderella_scean_01");
				slot = skeleton.findSlot("BG_back");
				bone = slot.getBone();
				att = slot.getAttachment();
			} else // littlePrincess_2
			if (skeletonData.getName().equals("littlePrincess_2")) {
				System.err.println("littlePrincess_2");
				slot = skeleton.findSlot("bg3");
				bone = slot.getBone();
				att = slot.getAttachment();
			} else // hook_2
			if (skeletonData.getName().equals("hook_2")) {
				System.err.println("hook_2");
				slot = skeleton.findSlot("images/bg02");
				bone = slot.getBone();
				att = slot.getAttachment();
			}  else // templeOfTheSkyPriest_1
				if (skeletonData.getName().equals("templeOfTheSkyPriest_1")) {
					System.err.println("templeOfTheSkyPriest_1");
					slot = skeleton.findSlot("BG5");
					bone = slot.getBone();
					att = slot.getAttachment();
			} else if (null != skeleton.findSlot("BG")) {
				System.err.println("BG");
				slot = skeleton.findSlot("BG");
				bone = slot.getBone();
				att = slot.getAttachment();
			} else if (null != skeleton.findSlot("bg")) {
				System.err.println("bg");
				slot = skeleton.findSlot("bg");
				bone = slot.getBone();
				att = slot.getAttachment();
			}
			if (bone != null && att instanceof RegionAttachment) {
				RegionAttachment ratt = (RegionAttachment) att;

				float[] worldVertices = new float[8];
				ratt.computeWorldVertices(bone, worldVertices, 0, 2);

				float maxX = Float.MIN_VALUE;
				float minX = Float.MAX_VALUE;
				float maxY = Float.MIN_VALUE;
				float minY = Float.MAX_VALUE;

				for (int i = 0; i < 4; i++) {
					float _x = worldVertices[2 * i];
					float _y = worldVertices[2 * i + 1];
					if (_x > maxX)
						maxX = _x;
					if (_x < minX)
						minX = _x;
					if (_y > maxY)
						maxY = _y;
					if (_y < minY)
						minY = _y;
				}
				fw = maxX - minX;
				fh = maxY - minY;

				x = (worldVertices[0] + worldVertices[2] + worldVertices[4] + worldVertices[6]) / 4;
				y = (worldVertices[1] + worldVertices[3] + worldVertices[5] + worldVertices[7]) / 4;

			} else if ((bone != null && att instanceof MeshAttachment)) {
				MeshAttachment matt = (MeshAttachment) att;

				int nVerticesValues = matt.getHullLength();
				float[] worldVertices = new float[nVerticesValues];
				float maxX = Float.MIN_VALUE;
				float minX = Float.MAX_VALUE;
				float maxY = Float.MIN_VALUE;
				float minY = Float.MAX_VALUE;
				matt.computeWorldVertices(slot, 0, nVerticesValues, worldVertices, 0, 2);
				for (int i = 0; i < (nVerticesValues / 2); i++) {
					float _x = worldVertices[2 * i];
					float _y = worldVertices[2 * i + 1];
					if (_x > maxX)
						maxX = _x;
					if (_x < minX)
						minX = _x;
					if (_y > maxY)
						maxY = _y;
					if (_y < minY)
						minY = _y;
				}

				x = (maxX + minX) / 2.0f;
				y = (maxY + minY) / 2.0f;
				fw = maxX - minX;
				fh = maxY - minY;

			} else {
				method = ViewportMethod.AABB;
				System.err.println("AABB");
				Vector2 xy = new Vector2();
				Vector2 wh = new Vector2();
				skeleton.getBounds(xy, wh, new FloatArray());
				fw = wh.x;
				fh = wh.y;
				x = xy.x + fw / 2;
				y = xy.y + fh / 2;
			}
		}
		whxy[0] = fw;
		whxy[1] = fh;
		whxy[2] = x;
		whxy[3] = y;
		return method;
	}

	private void recalibrateCameras(float[] whxy) {

		float fw, fh, x, y;
		fw = whxy[0];
		fh = whxy[1];
		x = whxy[2];
		y = whxy[3];

		int w = Math.round(fw);
		int h = Math.round(fh);

		// truncate size to even numbers

		if ((w % 2) != 0)
			w -= 1;
		if ((h % 2) != 0)
			h -= 1;

//		Gdx.graphics.setWindowedMode(w, h);
		camera.setToOrtho(false, w, h);
		camera.zoom = 1;
		camera.position.x = x;
		camera.position.y = y;
		camera.position.z = 0;
		camera.update();

		screenCamera.setToOrtho(false);
		screenCamera.zoom = (float) Math.max((double) w / (double) Gdx.graphics.getBackBufferWidth(),
				(double) h / (double) Gdx.graphics.getBackBufferHeight());
		screenCamera.position.x = camera.position.x;
		screenCamera.position.y = camera.position.y;
		screenCamera.position.z = 0;
		screenCamera.update();

	}

	private void loadSkeleton(final FileHandle skeletonFile) {

		System.err.println(String.format("skeleton: %s", skeletonFile.toString()));

		// Setup a texture atlas that uses a white image for images not found in the
		// atlas.
		Pixmap pixmap = new Pixmap(32, 32, Format.RGBA8888);
		pixmap.setColor(new Color(1, 1, 1, 0.33f));
		pixmap.fill();
		final AtlasRegion fake = new AtlasRegion(new Texture(pixmap), 0, 0, 32, 32);
		pixmap.dispose();

		FileHandle atlasFile = skeletonFile.sibling(skeletonFile.nameWithoutExtension() + ".atlas");
		TextureAtlasData data = !atlasFile.exists() ? null : new TextureAtlasData(atlasFile, atlasFile.parent(), false);
		
		for (Object t : loadedAtlas) {
			if (t instanceof TextureAtlas) {
				((TextureAtlas) t).dispose();
			} else
			if (t instanceof Texture) {
				((Texture) t).dispose();
			}
		}
		loadedAtlas.clear();
		for (Pixmap p : tmpTextures.values()) {
			p.dispose();
		}
		tmpTextures.clear();

		final TextureAtlas atlas = new TextureAtlas(data)
		{
			public AtlasRegion findRegion(String name) {
				AtlasRegion region = super.findRegion(name);
				if (region == null) {
					// Look for separate image file.
					FileHandle file = skeletonFile.sibling(name + ".png");
					if (file.exists()) {
						Texture texture = new Texture(file);
						texture.setFilter(TextureFilter.Linear, TextureFilter.Linear);
						region = new AtlasRegion(texture, 0, 0, texture.getWidth(), texture.getHeight());
						region.name = name;
					}
				}
				return region != null ? region : fake;
			}
		};
		for (Texture texture : atlas.getTextures())
			texture.setFilter(TextureFilter.Linear, TextureFilter.Linear);
		loadedAtlas.add(atlas);
		loadedAtlas.add(fake.getTexture());
		
//		SkeletonJson json = new SkeletonJson(atlas);
		SkeletonJson json = new SkeletonJson(new AtlasAttachmentLoader(atlas) {
			
			@Override
			public MeshAttachment newMeshAttachment (com.esotericsoftware.spine.Skin skin, String name, String path) {
				AtlasRegion region = atlas.findRegion(path);
				if (region == null) throw new RuntimeException("Region not found in atlas: " + path + " (mesh attachment: " + name + ")");
				// check if size matches
				if (region.rotate) {
					if (region.originalHeight != region.packedWidth || region.originalWidth != region.packedHeight) {
						Pixmap p1 = new Pixmap(region.originalHeight, region.originalWidth, Format.RGBA8888);
						p1.setColor(new Color(0, 0, 0, 0f));
						p1.fill();
						Pixmap p2;
						if (tmpTextures.containsKey(region.getTexture())) {
							p2 = tmpTextures.get(region.getTexture());
						} else {
							TextureData td = region.getTexture().getTextureData();
							td.prepare();
							p2 = td.consumePixmap();
							tmpTextures.put(region.getTexture(), p2);
						}
						p1.drawPixmap(p2,
								region.originalHeight - (int)region.offsetY - region.packedWidth,
								region.originalWidth - (int)region.offsetX - region.packedHeight,
								region.getRegionX(), region.getRegionY(), region.packedWidth, region.packedHeight);
						Texture t = new Texture(p1);
						t.setFilter(TextureFilter.Linear, TextureFilter.Linear);
						String regName = region.name;
						region = new AtlasRegion(t, 0, 0, t.getWidth(), t.getHeight());
						region.name = regName;
						region.rotate = true;
						region.degrees = 90;
						region.originalWidth = region.packedHeight;
						region.originalHeight = region.packedWidth;
						p1.dispose();
						loadedAtlas.add(t);
					}
				} else {
					if (region.originalWidth != region.packedWidth || region.originalHeight != region.packedHeight) {
						Pixmap p1 = new Pixmap(region.originalWidth, region.originalHeight, Format.RGBA8888);
						p1.setColor(new Color(0, 0, 0, 0f));
						p1.fill();
						Pixmap p2;
						if (tmpTextures.containsKey(region.getTexture())) {
							p2 = tmpTextures.get(region.getTexture());
						} else {
							TextureData td = region.getTexture().getTextureData();
							td.prepare();
							p2 = td.consumePixmap();
							tmpTextures.put(region.getTexture(), p2);
						}
						p1.drawPixmap(p2,
								(int)region.offsetX,
								region.originalHeight - (int)region.offsetY - region.packedHeight,
								region.getRegionX(), region.getRegionY(), region.packedWidth, region.packedHeight);
						Texture t = new Texture(p1);
						t.setFilter(TextureFilter.Linear, TextureFilter.Linear);
						String regName = region.name;
						region = new AtlasRegion(t, 0, 0, t.getWidth(), t.getHeight());
						region.name = regName;
						p1.dispose();
						loadedAtlas.add(t);
					}
				}
				MeshAttachment attachment = new MeshAttachment(name);
				attachment.setRegion(region);
				return attachment;
			}
		});
		SkeletonData skeletonData = json.readSkeletonData(skeletonFile);
		skeleton = new Skeleton(skeletonData);
		skeleton.setToSetupPose();
		skeleton.updateWorldTransform();

		state = new AnimationState(new AnimationStateData(skeletonData));
		this.animations = skeletonData.getAnimations();
		skeleton.setSkin(skeletonData.getDefaultSkin());

		// configure x, y, width, height based on bounding box at setup pose or
		// background texture

		doRecalibrate = false;
		float[] whxy = new float[4];
		ViewportMethod method = computeViewport(skeleton, whxy);
		if (method.equals(ViewportMethod.Background)) {
			doRecalibrate = true;
		}
		recalibrateCameras(whxy);
		int w = Math.round(whxy[0]);
		int h = Math.round(whxy[1]);

		// truncate size to even numbers

		if ((w % 2) != 0)
			w -= 1;
		if ((h % 2) != 0)
			h -= 1;

		this.width = w;
		this.height = h;

		System.err.println(String.format("%f %f", camera.position.x, camera.position.y));
		System.err.println(String.format("%d %d", width, height));

		if (fbo != null) {
			fbo.dispose();
		}
		fbo = new FrameBuffer(Format.RGB888, width, height, false);
		fbo.getColorBufferTexture().setFilter(TextureFilter.Linear, TextureFilter.Linear);

		state.getData().setDefaultMix(0.0f);
		renderer.setPremultipliedAlpha(true);
		batch.setPremultipliedAlpha(true);
	}

	private void setAnimation(Animation a) {
		TrackEntry current = state.getCurrent(0);
		TrackEntry entry;
		if (current == null) {
			state.setEmptyAnimation(0, 0);
			entry = state.addAnimation(0, a, false, 0);
			entry.setMixDuration(0.0f);
		} else {
			entry = state.setAnimation(0, a, false);
		}
		entry.setAlpha(1.0f);
	}

	private Pixmap renderAndFetch(boolean fetch, boolean doRecalibrate) {
		float delta = 0.032f;

		skeleton.update(delta);
		state.update(delta);
		state.apply(skeleton);
		skeleton.updateWorldTransform();
		if (doRecalibrate) {
			float[] whxy = new float[4];
			computeViewport(skeleton, whxy);
			recalibrateCameras(whxy);
		}
		batch.getProjectionMatrix().set(camera.combined);
		if (fetch) {
			fbo.begin();
			Gdx.gl.glClearColor(112 / 255f, 111 / 255f, 118 / 255f, 1);
			Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
			batch.begin();
			renderer.draw(batch, skeleton);
			batch.end();
			Pixmap buf;
			buf = getFrameBufferAsPixmap(0, 0, width, height);
			fbo.end();
			return buf;
		} else {
			return null;
		}
	}

	private void renderPreview() {
		Gdx.gl.glClearColor(112 / 255f, 111 / 255f, 118 / 255f, 1);
		Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
		screenCamera.update();
		batch.getProjectionMatrix().set(screenCamera.combined);
		batch.begin();
		renderer.draw(batch, skeleton);
		batch.end();
	}

	private void trySendImageFrame(Frame newFrame) {
		boolean success = imagesToWrite.offer(newFrame);
		if (!success) {
			frameBlocked[0] = newFrame;
		}
	}

	private void trySendEncodeFrame(Frame newFrame) {
		boolean success = framesToEncode.offer(newFrame);
		if (!success) {
			frameBlocked[1] = newFrame;
		}
	}
	
	@Override
	public void pause() {
		this.paused = true;
	}
	
	@Override
	public void resume() {
		this.paused = false;
	}

	@Override
	public void render() {
		try {
			if (frameBlocked[0] != null) {
				boolean success = imagesToWrite.offer(frameBlocked[0]);
				if (success) {
					frameBlocked[0] = null;
				} else {
					return;
				}
			}
			if (frameBlocked[1] != null) {
				boolean success = framesToEncode.offer(frameBlocked[1]);
				if (success) {
					frameBlocked[1] = null;
				} else {
					return;
				}
			}
			if (isDone)
				return;

			if (currentAnimationIndex >= animations.size) {
				currentSkeletonIndex += 1;
				isFirst = true;
				currentAnimationIndex = 0;
				if (currentSkeletonIndex >= skeletonFiles.size()) {
					isDone = true;
					trySendImageFrame(Frame.EOFFrame);
					trySendEncodeFrame(Frame.EOFFrame);
					return;
				} else {
					loadSkeleton(skeletonFiles.get(currentSkeletonIndex));
					setAnimation(animations.first());
					isFirstFrame = true;
				}
			}

			if (isFirst || animations.get(currentAnimationIndex).getDuration() == 0.0f) {
				boolean isImageInAnimation = false;
				if (animations.get(currentAnimationIndex).getDuration() == 0.0f) { // duration == 0
					isImageInAnimation = true;
				}
				isFirst = false;
				Pixmap pm = renderAndFetch(!noImage, isImageInAnimation && doRecalibrate);

				Frame newFrame;
				if (isImageInAnimation) {
					newFrame = new Frame(pm, Frame.Type.Image, currentSkeletonIndex,
							currentAnimationIndex, 0);
					currentAnimationIndex += 1;
					if (currentAnimationIndex < animations.size) {
						setAnimation(animations.get(currentAnimationIndex));
					}
				} else {
					newFrame = new Frame(pm, Frame.Type.Image, currentSkeletonIndex, 0, 0);
				}
				if (!noImage) {
					trySendImageFrame(newFrame);
				}
			} else {
				Pixmap pm = renderAndFetch(!noAnimation, isFirstFrame && doRecalibrate);
				isFirstFrame = false;
				Frame newFrame = new Frame(pm, Frame.Type.Frame, currentSkeletonIndex,
						currentAnimationIndex, currentFrameIndex);
				if (!noAnimation) {
					trySendEncodeFrame(newFrame);
				}
				currentFrameIndex += 1;
				if (state.getCurrent(0) != null && state.getCurrent(0).isComplete()) {
					currentAnimationIndex += 1;
					currentFrameIndex = 0;
					if (currentAnimationIndex < animations.size) {
						setAnimation(animations.get(currentAnimationIndex));
						isFirstFrame = true;
					}
				}
			}
		} finally {
			if (!paused) {
				renderPreview();
			}
		}

	}

	@Override
	public void resize(int width, int height) {
		float x = screenCamera.position.x;
		float y = screenCamera.position.y;
		screenCamera.setToOrtho(false);
		screenCamera.position.set(x, y, 0);
		screenCamera.zoom = (float) Math.max((double) this.width / (double) width,
				(double) this.height / (double) height);
	}

}
