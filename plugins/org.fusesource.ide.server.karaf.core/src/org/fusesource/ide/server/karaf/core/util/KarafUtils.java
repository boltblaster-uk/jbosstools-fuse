/*******************************************************************************
 * Copyright (c) 2013 Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Red Hat, Inc. - initial API and implementation
 ******************************************************************************/

package org.fusesource.ide.server.karaf.core.util;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.jar.Manifest;

import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionResult;
import org.apache.maven.model.Model;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.m2e.core.MavenPlugin;
import org.eclipse.m2e.core.embedder.IMaven;
import org.eclipse.m2e.core.embedder.IMavenExecutionContext;
import org.eclipse.m2e.core.internal.IMavenConstants;
import org.eclipse.wst.server.core.IModule;
import org.eclipse.wst.server.core.IServer;
import org.eclipse.wst.server.core.model.ServerBehaviourDelegate;
import org.fusesource.ide.commons.util.Strings;
import org.fusesource.ide.server.karaf.core.server.BaseConfigPropertyProvider;
import org.jboss.ide.eclipse.as.core.server.bean.ServerBeanLoader;
import org.jboss.ide.eclipse.as.core.server.bean.ServerBeanType;

/**
 * @author lhein
 */
public class KarafUtils {
	
	/**
	 * A constant representing that no publish is required. 
	 * This constant is different from the wtp constants in that
	 * this constant is used after taking into account 
	 * the server flags of kind and deltaKind, as well as the module restart state,
	 * to come to a conclusion of what a publisher needs to do.
	 */
	public static final int NO_PUBLISH = 0;
	/**
	 * A constant representing that an incremental publish is required. 
	 * This constant is different from the wtp constants in that
	 * this constant is used after taking into account 
	 * the server flags of kind and deltaKind, as well as the module restart state,
	 * to come to a conclusion of what a publisher needs to do.
	 */
	public static final int INCREMENTAL_PUBLISH = 1;
	/**
	 * A constant representing that a full publish is required. 
	 * This constant is different from the wtp constants in that
	 * this constant is used after taking into account 
	 * the server flags of kind and deltaKind, as well as the module restart state,
	 * to come to a conclusion of what a publisher needs to do.
	 */
	public static final int FULL_PUBLISH = 2;
	
	/**
	 * A constant representing that a removal-type publish is required. 
	 * This constant is different from the wtp constants in that
	 * this constant is used after taking into account 
	 * the server flags of kind and deltaKind, as well as the module restart state,
	 * to come to a conclusion of what a publisher needs to do.
	 */
	public static final int REMOVE_PUBLISH = 3;
	
	/**
	 * the protocol prefix for deployments
	 */
	public static final String PROTOCOL_PREFIX_JAR 		= "wrap:";
	public static final String PROTOCOL_PREFIX_MAVEN 	= "mvn:";
	public static final String PROTOCOL_PREFIX_OSGI 	= "";
	public static final String PROTOCOL_PREFIX_WEB 		= "war:";
	
	/**
	 * packaging types
	 */
	public static final String PACKAGING_JAR			= "jar";
	public static final String PACKAGING_BUNDLE			= "bundle";
	public static final String PACKAGING_WAR			= "war";
	
	
	/**
	 * retrieves the version of the runtime installation
	 * 
	 * @param installFolder	the installation folder
	 * @return	the version string or null on errors
	 */
	public static String getVersion(File installFolder) {
		ServerBeanLoader loader = new ServerBeanLoader(installFolder);
		if( loader.getServerBeanType() != ServerBeanType.UNKNOWN) {
			return loader.getFullServerVersion();
		}
		return null;
	}
	
	/**
	 * Given the various flags, return which of the following options 
	 * our publishers should perform:
	 *    1) A full publish
	 *    2) A removed publish (remove the module)
	 *    3) An incremental publish, or
	 *    4) No publish at all. 
	 * @param module
	 * @param kind
	 * @param deltaKind
	 * @return
	 */
	public static int getPublishType(IServer server, IModule[] module, int kind, int deltaKind) {
		int modulePublishState = server.getModulePublishState(module);
		if( deltaKind == ServerBehaviourDelegate.ADDED ) 
			return FULL_PUBLISH;
		else if (deltaKind == ServerBehaviourDelegate.REMOVED) {
			return REMOVE_PUBLISH;
		} else if (kind == IServer.PUBLISH_FULL 
				|| modulePublishState == IServer.PUBLISH_STATE_FULL 
				|| kind == IServer.PUBLISH_CLEAN ) {
			return FULL_PUBLISH;
		} else if (kind == IServer.PUBLISH_INCREMENTAL 
				|| modulePublishState == IServer.PUBLISH_STATE_INCREMENTAL 
				|| kind == IServer.PUBLISH_AUTO) {
			if( ServerBehaviourDelegate.CHANGED == deltaKind ) 
				return INCREMENTAL_PUBLISH;
		} 
		return NO_PUBLISH;
	}
	
	/**
	 * 
	 * @param module
	 * @return
	 */
	public static String getBundleFilePath(final IModule module) throws CoreException {
		final String packaging = getPackaging(module);
		File projectTargetPath = module.getProject().getLocation().append("target").toFile();
		File[] jars = projectTargetPath.listFiles(new FileFilter() {
			/*
			 * (non-Javadoc)
			 * @see java.io.FileFilter#accept(java.io.File)
			 */
			@Override
			public boolean accept(File pathname) {
				return pathname.exists() && pathname.isFile() && pathname.getName().toLowerCase().startsWith(module.getProject().getName()) && pathname.getName().toLowerCase().endsWith(getFileExtensionForPackaging(packaging));
			}
		});
		if (jars.length>0) {
			if (packaging.equalsIgnoreCase(PACKAGING_BUNDLE)) {
				return String.format("%sfile:%s", getProtocolPrefixForModule(module), jars[0].getPath());
			} else if (packaging.equalsIgnoreCase(PACKAGING_JAR)) {
				return String.format("%sfile:%s$Bundle-SymbolicName=%s&Bundle-Version=%s", getProtocolPrefixForModule(module), jars[0].getPath(), module.getProject().getName(), getBundleVersion(module, jars[0]));
			} else if (packaging.equalsIgnoreCase(PACKAGING_WAR)) {
				return String.format("%sfile:%s?Bundle-SymbolicName=%s&Bundle-Version=%s", getProtocolPrefixForModule(module), jars[0].getPath(), module.getProject().getName(), getBundleVersion(module, jars[0]));	
			}			
		}
		return null;
	}

	/**
	 * returns the default file extension for the built artifact of a given packaging type
	 * 
	 * @param packaging
	 * @return
	 */
	private static String getFileExtensionForPackaging(String packaging) {
		if (packaging.equalsIgnoreCase(PACKAGING_BUNDLE) || packaging.equalsIgnoreCase(PACKAGING_JAR)) {
			return ".jar";
		} else if (packaging.equalsIgnoreCase(PACKAGING_WAR)) {
			return ".war";
		} else {
			return ".jar";
		}
	}
	
	/**
	 * returns the protocol prefix for deployments like WRAP: or WAR:
	 * 
	 * @param module
	 * @return
	 */
	private static String getProtocolPrefixForModule(IModule module) throws CoreException {	
		String packaging = getPackaging(module);
		if (Strings.isBlank(packaging) || packaging.equalsIgnoreCase(PACKAGING_JAR)) {
			return PROTOCOL_PREFIX_JAR;
		} else if (packaging.equalsIgnoreCase(PACKAGING_BUNDLE)) {
			return PROTOCOL_PREFIX_OSGI;
		} else if (packaging.equalsIgnoreCase(PACKAGING_WAR)) {
			return PROTOCOL_PREFIX_WEB;
		} else {
			return PROTOCOL_PREFIX_JAR;
		}
	}
	
	/**
	 * returns the packaging of the maven project
	 * 
	 * @param module
	 * @return
	 * @throws CoreException
	 */
	private static String getPackaging(IModule module) throws CoreException {
		Model model = MavenPlugin.getMavenModelManager().readMavenModel(getModelFile(module));
		return model.getPackaging();
	}
	
	/**
	 * returns a file reference to the maven pom file of the module
	 * @param module
	 * @return
	 */
	public static File getModelFile(IModule module) {
		return module.getProject().getLocation().append(IMavenConstants.POM_FILE_NAME).toFile();
	}
	
	/**
	 * runs the maven build to create the jar file for the module
	 * @param module
	 * @param monitor
	 * @return
	 * @throws CoreException
	 */
	public static boolean runBuild(IModule module, IProgressMonitor monitor)  throws CoreException {
		IMaven maven = MavenPlugin.getMaven();
		IMavenExecutionContext executionContext = maven.createExecutionContext();
		MavenExecutionRequest executionRequest = executionContext.getExecutionRequest();
		executionRequest.setPom(getModelFile(module));
		executionRequest.setGoals(Arrays.asList("clean", "package"));
		MavenExecutionResult result = maven.execute(executionRequest, monitor);
		return !result.hasExceptions();
	}

	/**
	 * 
	 * @param module
	 * @param f
	 * @return
	 */
	public static String getBundleVersion(IModule module, File f) throws CoreException {
		String version = null;
		String packaging = getPackaging(module);
		IFile manifest = module.getProject().getFile("target/classes/META-INF/MANIFEST.MF");
		if (!manifest.exists()) {
			manifest = module.getProject().getFile("META-INF/MANIFEST.MF");
		}
		if (manifest.exists()) {
			try {
				Manifest mf = new Manifest(new FileInputStream(manifest.getLocation().toFile()));
				version = mf.getMainAttributes().getValue("Bundle-Version");
			} catch (IOException ex) {
				version = null;
			}
		} else {
			// no OSGi bundle - lets take the project name instead
			version = null;
		}

		if (version == null) {
			// no manifest - so grab it from the file name
			if (f != null) {
				if (version == null) {
					version = "";
					String[] parts = f.getName().split("-");
					for (String part : parts) {
						if (!Character.isDigit(part.charAt(0))) {
							if (version.length()==0) continue;
							version += "." + part;
						}
						version += part.trim();
					}
					version = version.substring(0, version.indexOf(getFileExtensionForPackaging(packaging)));
				}
			} else {
				// no file...parse it from the bundle url
				String uri = getBundleFilePath(module);
				if (uri.indexOf("Bundle-Version=") != -1) {
					version = uri.substring(uri.indexOf("Bundle-Version=") + "Bundle-Version=".length());
				}
			}
		}
		
		return version;
	}

	/**
	 * retrieve all needed information to connect to JMX server
	 * @param server
	 * @return
	 */
	public static String getJMXConnectionURL(IServer server) {
		String retVal = "";
		BaseConfigPropertyProvider manProv = new BaseConfigPropertyProvider(server.getRuntime().getLocation().append("etc").append("org.apache.karaf.management.cfg").toFile());
		BaseConfigPropertyProvider sysProv = new BaseConfigPropertyProvider(server.getRuntime().getLocation().append("etc").append("system.properties").toFile());
		
		String url = manProv.getConfigurationProperty("serviceUrl");
		if (url == null) return null;
		url = url.trim();
		int pos = -1;
		while ((pos = url.indexOf("${")) != -1) {
			retVal += url.substring(0, pos);
			String placeHolder = url.substring(url.indexOf("${")+2, url.indexOf("}")).trim();
			String replacement = manProv.getConfigurationProperty(placeHolder);
			if (replacement == null) {
				replacement = sysProv.getConfigurationProperty(placeHolder);
			}
			if (replacement == null) {
				return null;
			} else {
				retVal += replacement.trim();
				url = url.substring(pos + placeHolder.length() + 3);
			}
		}
		return retVal;
	}
}