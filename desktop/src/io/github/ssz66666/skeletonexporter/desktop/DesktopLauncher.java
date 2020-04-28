package io.github.ssz66666.skeletonexporter.desktop;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import com.badlogic.gdx.backends.lwjgl.LwjglApplication;
import com.badlogic.gdx.backends.lwjgl.LwjglApplicationConfiguration;

import io.github.ssz66666.skeletonexporter.SkeletonOutputRenderer;
import io.github.ssz66666.skeletonexporter.SkeletonOutputRenderer.InputStreamDrain;;


public class DesktopLauncher {
	public static void main (String[] args) throws Exception {
		LwjglApplicationConfiguration.disableAudio = true;
		LwjglApplicationConfiguration config = new LwjglApplicationConfiguration();
		config.width = (int)(800);
		config.height = (int)(600);
		config.title = "Skeleton Viewer";
		config.allowSoftwareMode = true;
		config.pauseWhenBackground = false;
		config.pauseWhenMinimized = false;
		config.foregroundFPS = 60;
		config.backgroundFPS = 0;
//		config.backgroundFPS = 0;
//		config.foregroundFPS = 0;
//		new LwjglApplication(new SkeletonViewer(), config);
		
//		new LwjglApplication(new MemTest(), config);
		
		
		Options options = new Options();
		Option input = Option.builder("i")
								.longOpt("input")
								.hasArg()
								.argName("path")
								.desc("input spine json file or directory to search for spine skeletons, must be specified")
								.required()
								.build();
		Option outdir = Option.builder("d")
								.longOpt("dir")
								.hasArg()
								.argName("directory")
								.desc("the output directory")
								.build();
		Option output = Option.builder("o")
								.longOpt("output")
								.hasArg()
								.argName("pattern")
								.desc("the output file pattern")
								.build();
		Option ffmpeg = Option.builder()
								.longOpt("ffmpeg")
								.hasArg()
								.desc("path to the ffmpeg binary. If not specified, the bundled slow library will be used.")
								.required()
								.build();
		Option help = Option.builder("h")
								.longOpt("help")
								.hasArg(false)
								.desc("show this message")
								.build();
		options.addOption(input);
		options.addOption(outdir);
		options.addOption(output);
		options.addOption(ffmpeg);
		options.addOption(help);
		
		CommandLineParser parser = new DefaultParser();
		HelpFormatter helpFormatter = new HelpFormatter();
		Path inputPath = null;
		Path outputPath = null;
		String outputPattern = null;
		String ffmpegPath = null;
		List<String> ffmpegArgs;
		try {
			CommandLine line = parser.parse(options, args, true);
			if( line.hasOption("help")) {
				helpFormatter.printHelp("spine-exporter  -i          INPUT_DIR\t[-d/--dir    OUTPUT_DIR]\t[-o/--output OUTPUT_PATTERN]\t--ffmpeg    PATH_TO_FFMPEG_BINARY\t[ffmpeg output options (excluding output path)]", options);
				System.exit(0);
			}
			inputPath = FileSystems.getDefault().getPath(line.getOptionValue("input"));
			if (!inputPath.toFile().canRead()) {
				System.err.println("The specified input does not exist or is not readable.");
				System.exit(-1);
			}
			outputPath = FileSystems.getDefault().getPath(line.getOptionValue("dir", ""));
			outputPattern = line.getOptionValue("output", "%2$04d_%3$02d_%1$s.mp4");
			if (line.hasOption("ffmpeg")) {
				ffmpegPath = line.getOptionValue("ffmpeg");
				try {
					Process p = Runtime.getRuntime().exec(new String[] {ffmpegPath, "-h"});
					p.getOutputStream().close();
					new Thread(new InputStreamDrain(p.getInputStream())).run();
					new Thread(new InputStreamDrain(p.getErrorStream())).run();
					if (p.waitFor() != 0) {
						throw new IOException();
					}
				} catch (IOException ioe) {
					System.err.println(String.format("ffmpeg path \"%s\" does not exist or is not executable.", ffmpegPath));
					System.exit(-1);
				}
			}
			ffmpegArgs = line.getArgList();
			if (ffmpegArgs.isEmpty()) {
				ffmpegArgs = Arrays.asList(
						new String[] {
								"-c:v", "libx264",
								"-crf", "22",
								"-pix_fmt", "yuv420p"});
			}
			new LwjglApplication(new SkeletonOutputRenderer(inputPath, outputPath, outputPattern, ffmpegPath, ffmpegArgs), config);
			
		} catch (ParseException exp) {
//			exp.printStackTrace();
			helpFormatter.printHelp("spine-exporter  -i          INPUT_DIR\t[-d/--dir    OUTPUT_DIR]\t[-o/--output OUTPUT_PATTERN]\t--ffmpeg    PATH_TO_FFMPEG_BINARY\t[ffmpeg output options (excluding output path)]", options);
		}
		
	}
}
