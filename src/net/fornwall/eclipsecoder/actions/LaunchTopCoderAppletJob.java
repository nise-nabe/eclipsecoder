package net.fornwall.eclipsecoder.actions;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;

import net.fornwall.eclipsecoder.preferences.EclipseCoderPlugin;
import net.fornwall.eclipsecoder.util.Utilities;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.osgi.framework.Bundle;

/**
 * Job to launch the TopCoder contest applet. Downloads on demand from
 * http://www.topcoder.com/contest/classes/ContestApplet.jar and delegates
 * actual launching to {@link TopCoderAppletLauncher}.
 */
public class LaunchTopCoderAppletJob extends Job {

	private static final String APPLET_BASE_URL = "http://www.topcoder.com/contest/classes/7.0/";
	private static final String[] JarNames = { "arena-client-7.0.0.jar", "basic_type_serialization-1.0.1.jar",
			"client-socket-SNAPSHOT.jar", "concurrent-SNAPSHOT.jar", "encoder-SNAPSHOT.jar",
			"arena-shared-SNAPSHOT.jar", "client-common-SNAPSHOT.jar", "compeng-common-SNAPSHOT.jar",
			"custom-serialization-SNAPSHOT.jar", "http-tunnel-client-SNAPSHOT.jar", "log4j-1.2.13.jar", };

	private static final int ESTIMATED_APPLET_SIZE = 2000000;

	private static boolean classLoaderWorks(ClassLoader loader) {
		try {
			loader.loadClass("com.topcoder.client.contestApplet.runner.generic");
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	private static File getLocalAppletJar(String filename) {
		File userHome = new File(System.getProperty("user.home"));
		File eclipsecoderDir = new File(userHome, ".eclipsecoder");
		if (((eclipsecoderDir.exists() && eclipsecoderDir.isDirectory()) || eclipsecoderDir.mkdir())
				&& (eclipsecoderDir.canRead() && eclipsecoderDir.canWrite())) {
			// try with global jar location outside workspace
			return new File(eclipsecoderDir, filename);
		}

		IPath stateLocation = EclipseCoderPlugin.getDefault().getStateLocation();
		IPath jarLocation = stateLocation.append(filename);
		return jarLocation.toFile();
	}

	public LaunchTopCoderAppletJob(String name) {
		super(name);
	}

	private IStatus download(IProgressMonitor monitor) {
		InputStream in = null;
		OutputStream out = null;

		for (String jarName : JarNames) {
			try {
				URLConnection connection = new URL(APPLET_BASE_URL + jarName).openConnection();
				int jarSize = (connection.getContentLength() == -1) ? ESTIMATED_APPLET_SIZE : connection
						.getContentLength();
				monitor.beginTask("Downloading applet", jarSize);

				in = connection.getInputStream();
				out = new FileOutputStream(getLocalAppletJar(jarName));
				byte[] buffer = new byte[2048];
				int i;
				while ((i = in.read(buffer)) != -1) {
					if (monitor.isCanceled()) {
						Utilities.close(out);
						if (getLocalAppletJar(jarName).exists()) {
							if (!getLocalAppletJar(jarName).delete()) {
								Utilities.showMessageDialog("Cannot delete file", "Cannot delete applet file:\n"
										+ getLocalAppletJar(jarName).getAbsolutePath()
										+ "\n\nCheck permissions if problem persists.", true);
							}
						}
						StartTopCoderAppletAction.getAction().setEnabled(true);
						return Status.CANCEL_STATUS;
					}
					out.write(buffer, 0, i);
					monitor.worked(i);
				}
			} catch (MalformedURLException e) {
				// should never happen as APPLET_URL is a valid URL
				if (getLocalAppletJar(jarName).exists()) {
					getLocalAppletJar(jarName).delete();
				}
				StartTopCoderAppletAction.getAction().setEnabled(true);
				Utilities.showException(e);
			} catch (IOException e) {
				if (getLocalAppletJar(jarName).exists()) {
					getLocalAppletJar(jarName).delete();
				}
				StartTopCoderAppletAction.getAction().setEnabled(true);
				return new Status(IStatus.ERROR, EclipseCoderPlugin.PLUGIN_ID, IStatus.OK, e.getMessage(), e);
			} finally {
				Utilities.close(in);
				Utilities.close(out);
			}
		}

		return null;
	}

	@Override
	protected IStatus run(IProgressMonitor monitor) {
		File localAppletJar = getLocalAppletJar(JarNames[0]);

		if (!localAppletJar.exists()) {
			IStatus status = download(monitor);
			if (status != null)
				return status;
		}

		if (!localAppletJar.exists()) {
			// should have been downloaded above
			return new Status(IStatus.ERROR, EclipseCoderPlugin.PLUGIN_ID, IStatus.OK, "Applet does not exist at "
					+ localAppletJar.getAbsolutePath(), null);
		}

		try {
			long lastModifiedLocal = localAppletJar.lastModified();
			URLConnection connection = new URL(APPLET_BASE_URL + JarNames[0]).openConnection();
			long lastModifiedRemote = connection.getLastModified();
			if (lastModifiedRemote > lastModifiedLocal) {
				IStatus status = download(monitor);
				if (status != null)
					return status;
			}
			URL[] loadURLs = new URL[JarNames.length + 1];
			for(int i = 0; i < JarNames.length; ++i){
				loadURLs[i] = getLocalAppletJar(JarNames[i]).toURI().toURL();
			}
			Bundle bundle = EclipseCoderPlugin.getDefault().getBundle();
			Path path = new Path("arenaplugin.jar");
			URL pluginURL = FileLocator.find(bundle, path, null);
			loadURLs[JarNames.length] = pluginURL;

			ClassLoader parent = Thread.currentThread().getContextClassLoader();
			ClassLoader loader = new URLClassLoader(loadURLs, parent);

			if (classLoaderWorks(loader)) {
				TopCoderAppletLauncher.run(loader);
			} else {
				StartTopCoderAppletAction.getAction().setEnabled(true);
				String message;

				boolean hasDeleteComplete = true;
				for (String jarName: JarNames) {
					hasDeleteComplete &= getLocalAppletJar(jarName).delete();
				}

				if (hasDeleteComplete) {
					message = "The downloaded applet jar could not be used and has been deleted. Try to start again.";
					// Utilities
					// .showMessageDialog(
					// "Error starting applet",
					// "The downloaded applet jar could not be used and has
					// been deleted. Try to start again.",
					// true);
				}
				// Utilities.showMessageDialog("Error starting applet",
				// "The downloaded applet jar could not be used and cannot
				// be removed - please check the file "
				// + getLocalAppletJar().getAbsolutePath(), true);

				message = "The downloaded applet jar could not be used and cannot be removed - please check the file ";
				for (String jarName: JarNames) {
					message += getLocalAppletJar(jarName).getAbsolutePath() + " ";
				}
				return new Status(IStatus.ERROR, EclipseCoderPlugin.PLUGIN_ID, IStatus.OK, message, null);
			}

		} catch (Exception e) {
			return new Status(IStatus.ERROR, EclipseCoderPlugin.PLUGIN_ID, IStatus.OK, e.getMessage(), e);
		}

		return Status.OK_STATUS;
	}
}
