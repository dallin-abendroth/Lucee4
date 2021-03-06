/**
 *
 * Copyright (c) 2014, the Railo Company Ltd. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either 
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public 
 * License along with this library.  If not, see <http://www.gnu.org/licenses/>.
 * 
 **/
package lucee.runtime.config;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import lucee.commons.io.IOUtil;
import lucee.commons.io.SystemUtil;
import lucee.commons.io.compress.ZipUtil;
import lucee.commons.io.log.Log;
import lucee.commons.io.log.LogUtil;
import lucee.commons.io.res.Resource;
import lucee.commons.io.res.filter.ExtensionResourceFilter;
import lucee.commons.io.res.filter.ResourceFilter;
import lucee.commons.io.res.util.FileWrapper;
import lucee.commons.io.res.util.ResourceUtil;
import lucee.commons.lang.ExceptionUtil;
import lucee.commons.lang.StringUtil;
import lucee.commons.lang.SystemOut;
import lucee.runtime.Info;
import lucee.runtime.extension.RHExtension;
import lucee.runtime.op.Caster;
import lucee.runtime.op.Decision;
import lucee.runtime.type.util.ListUtil;

public class DeployHandler {
	
	

	private static final ResourceFilter ALL_EXT = new ExtensionResourceFilter(new String[]{".lex",".lar"});


	public static void deploy(Config config){
		synchronized (config) {
			Resource dir = getDeployDirectory(config);
			int ma = Info.getMajorVersion();
			int mi = Info.getMinorVersion();
			if(!dir.exists()) {
				if(ma>4 || ma==4 && mi>1) {// FUTURE remove the if contition
					dir.mkdir(); // TODO we have set this to mkdir to avoid that delted context are recreated (the file structure), but the question is, why is this executed on a deleted context
				}
				return;
			}
			
			Resource[] children = dir.listResources(ALL_EXT);
			Resource child;
			String ext;
			for(int i=0;i<children.length;i++){
				child=children[i];
				try {
				// Lucee archives
				ext=ResourceUtil.getExtension(child, null);
				if("lar".equalsIgnoreCase(ext)) {
					deployArchive(config,child);
				}
				
				// Lucee Extensions
				else if("lex".equalsIgnoreCase(ext))
					deployExtension(config, child);
				}
				catch (ZipException e) {
					SystemOut.printDate(config.getErrWriter(),ExceptionUtil.getStacktrace(e, true));
				}
				catch (IOException e) {
					SystemOut.printDate(config.getErrWriter(),ExceptionUtil.getStacktrace(e, true));
				}
			}
		}
	}

	public static Resource getDeployDirectory(Config config) {
		return config.getConfigDir().getRealResource("deploy");
	}

	private static void deployArchive(Config config,Resource archive) throws ZipException, IOException {
		Log logger = ((ConfigImpl)config).getLog("deploy");
		String type=null,virtual=null,name=null;
		boolean readOnly,topLevel,hidden,physicalFirst;
		short inspect;
		InputStream is = null;
		ZipFile file=null;
		try {
		file=new ZipFile(FileWrapper.toFile(archive));
		ZipEntry entry = file.getEntry("META-INF/MANIFEST.MF");
		
		// no manifest
		if(entry==null) {
			logger.log(Log.LEVEL_ERROR,"archive","cannot deploy Lucee Archive ["+archive+"], file is to old, the file does not have a MANIFEST.");
			moveToFailedFolder(archive);
			return;
		}
		
			is = file.getInputStream(entry);
			Manifest manifest = new Manifest(is);
		    Attributes attr = manifest.getMainAttributes();
		    
		    //id = unwrap(attr.getValue("mapping-id"));
		    type = unwrap(attr.getValue("mapping-type"));
		    virtual = unwrap(attr.getValue("mapping-virtual-path"));
		    name = ListUtil.trim(virtual, "/");
		    readOnly = Caster.toBooleanValue(unwrap(attr.getValue("mapping-readonly")),false);
		    topLevel = Caster.toBooleanValue(unwrap(attr.getValue("mapping-top-level")),false);
		    inspect = ConfigWebUtil.inspectTemplate(unwrap(attr.getValue("mapping-inspect")), ConfigImpl.INSPECT_UNDEFINED);
		    if(inspect==ConfigImpl.INSPECT_UNDEFINED) {
		    	Boolean trusted = Caster.toBoolean(unwrap(attr.getValue("mapping-trusted")),null);
		    	if(trusted!=null) {
		    		if(trusted.booleanValue()) inspect=ConfigImpl.INSPECT_NEVER;
		    		else inspect=ConfigImpl.INSPECT_ALWAYS;
		    	}	
		    }
		    hidden = Caster.toBooleanValue(unwrap(attr.getValue("mapping-hidden")),false);
		    physicalFirst = Caster.toBooleanValue(unwrap(attr.getValue("mapping-physical-first")),false);
		}
		finally{
			IOUtil.closeEL(is);
			ZipUtil.close(file);
		}
		Resource trgDir = config.getConfigDir().getRealResource("archives").getRealResource(type).getRealResource(name);
		Resource trgFile = trgDir.getRealResource(archive.getName());
		trgDir.mkdirs();
		
		// delete existing files
		
		try {
			ResourceUtil.deleteContent(trgDir, null);
			ResourceUtil.moveTo(archive, trgFile,true);
			
			logger.log(Log.LEVEL_INFO,"archive","add "+type+" mapping ["+virtual+"] with archive ["+trgFile.getAbsolutePath()+"]");
			if("regular".equalsIgnoreCase(type))
				ConfigWebAdmin.updateMapping((ConfigImpl)config,virtual, null, trgFile.getAbsolutePath(), "archive", inspect, topLevel);
			else if("cfc".equalsIgnoreCase(type))
				ConfigWebAdmin.updateComponentMapping((ConfigImpl)config,virtual, null, trgFile.getAbsolutePath(), "archive", inspect);
			else if("ct".equalsIgnoreCase(type))
				ConfigWebAdmin.updateCustomTagMapping((ConfigImpl)config,virtual, null, trgFile.getAbsolutePath(), "archive", inspect);
			
		    
		}
		catch (Throwable t) {
        	ExceptionUtil.rethrowIfNecessary(t);
			moveToFailedFolder(archive);
			LogUtil.log(logger, Log.LEVEL_ERROR,"archive",t);
		}
	}
	

	private static void deployExtension(Config config, Resource ext) {
		ConfigImpl ci = (ConfigImpl)config;
		boolean isWeb=config instanceof ConfigWeb;
		String type=isWeb?"web":"server";
		Log logger = ((ConfigImpl)config).getLog("deploy");
		
		// Manifest
		Manifest manifest = null;
		ZipInputStream zis=null;
        try {
	        zis = new ZipInputStream( IOUtil.toBufferedInputStream(ext.getInputStream()) ) ;     
	        ZipEntry entry;
	        String name;
	        while ( ( entry = zis.getNextEntry()) != null ) {
	        	name=entry.getName();
	        	if(!entry.isDirectory() && name.equalsIgnoreCase("META-INF/MANIFEST.MF")) {
	        		manifest = toManifest(config,zis,null);
	        	}
	            zis.closeEntry() ;
	        }
        }
        catch(Throwable t){
        	ExceptionUtil.rethrowIfNecessary(t);
        	LogUtil.log(logger, Log.LEVEL_ERROR,"extension", t);
			moveToFailedFolder(ext);
			return;
        }
        finally {
        	IOUtil.closeEL(zis);
        }
        
        int minCoreVersion=0;
        double minLoaderVersion=0;
        String strMinCoreVersion="",strMinLoaderVersion="",version=null,name=null,id=null;
        
        if(manifest!=null) {
        	Attributes attr = manifest.getMainAttributes();
        	// version
        	version=unwrap(attr.getValue("version"));


        	// id
        	id=unwrap(attr.getValue("id"));
        	
        	// name
        	name=unwrap(attr.getValue("name"));

        	// core version
        	strMinCoreVersion=unwrap(attr.getValue("lucee-core-version"));
        	if(StringUtil.isEmpty(strMinCoreVersion))
        		strMinCoreVersion=unwrap(attr.getValue("railo-core-version"));
        	minCoreVersion=Info.toIntVersion(strMinCoreVersion,minCoreVersion);
        	
        	// loader version
        	strMinLoaderVersion=unwrap(attr.getValue("lucee-loader-version"));
        	if(StringUtil.isEmpty(strMinLoaderVersion))
        		strMinLoaderVersion=unwrap(attr.getValue("railo-loader-version"));
        	minLoaderVersion=Caster.toDoubleValue(strMinLoaderVersion,minLoaderVersion);
        	
        }
        if(StringUtil.isEmpty(name,true)) {
        	name=ext.getName();
        	int index=name.lastIndexOf('.');
        	name=name.substring(0,index-1);
        }
        name=name.trim();
        
        
        // check core version
		if(minCoreVersion>Info.getVersionAsInt()) {
			logger.log(Log.LEVEL_ERROR,"extension", "cannot deploy Lucee Extension ["+ext+"], Lucee Version must be at least ["+strMinCoreVersion+"].");
			moveToFailedFolder(ext);
			return;
		}

		// check loader version
		if(minLoaderVersion>SystemUtil.getLoaderVersion()) {
			logger.log(Log.LEVEL_ERROR,"extension", "cannot deploy Lucee Extension ["+ext+"], Lucee Loader Version must be at least ["+strMinLoaderVersion+"], update the lucee.jar first.");
			moveToFailedFolder(ext);
			return;
		}
		// check id
		if(!Decision.isUUId(id)) {
			logger.log(Log.LEVEL_ERROR,"extension", "cannot deploy Lucee Extension ["+ext+"], this Extension has no valid id ["+id+"],id must be a valid UUID.");
			moveToFailedFolder(ext);
			return;
		}
		
		
		
		
		Resource trgFile=null;
		try{
			ConfigWebAdmin.removeRHExtension(ci,id);
			
			Resource trgDir = config.getConfigDir().getRealResource("extensions").getRealResource(type).getRealResource(name);
			trgFile = trgDir.getRealResource(ext.getName());
			trgDir.mkdirs();
			ResourceUtil.moveTo(ext, trgFile,true);
		}
	    catch(Throwable t){
        	ExceptionUtil.rethrowIfNecessary(t);
	    	LogUtil.log(logger, Log.LEVEL_ERROR,"extension", t);
			moveToFailedFolder(ext);
			return;
	    }
	    
		try {
	        zis = new ZipInputStream( IOUtil.toBufferedInputStream(trgFile.getInputStream()) ) ;     
	        ZipEntry entry;
	        String path;
	        String fileName;
	        List<String> jars=new ArrayList<String>(), flds=new ArrayList<String>(), tlds=new ArrayList<String>(), contexts=new ArrayList<String>(), applications=new ArrayList<String>();
	        while ( ( entry = zis.getNextEntry()) != null ) {
	        	path=entry.getName();
	        	fileName=fileName(entry);
	        	// jars
	        	if(!entry.isDirectory() && (startsWith(path,type,"jars") || startsWith(path,type,"jar") || startsWith(path,type,"lib") || startsWith(path,type,"libs")) && StringUtil.endsWithIgnoreCase(path, ".jar")) {
	        		logger.log(Log.LEVEL_INFO,"extension","deploy jar "+fileName);
	        		ConfigWebAdmin.updateJar(config,zis,fileName,false);
	        		jars.add(fileName);
	        	}
	        	
	        	// flds
	        	if(!entry.isDirectory() && startsWith(path,type,"flds") && StringUtil.endsWithIgnoreCase(path, ".fld")) {
	        		logger.log(Log.LEVEL_INFO,"extension","deploy fld "+fileName);
	        		ConfigWebAdmin.updateFLD(config, zis, fileName,false);
	        		flds.add(fileName);
	        	}
	        	
	        	// tlds
	        	if(!entry.isDirectory() && startsWith(path,type,"tlds") && (StringUtil.endsWithIgnoreCase(path, ".tld") || StringUtil.endsWithIgnoreCase(path, ".tldx"))) {
	        		logger.log(Log.LEVEL_INFO,"extension","deploy tld "+fileName);
	        		ConfigWebAdmin.updateTLD(config, zis, fileName,false); 
	        		tlds.add(fileName);
	        	}
	        	
	        	// context
	        	String relpath;
	        	if(!entry.isDirectory() && startsWith(path,type,"context") && !StringUtil.startsWith(fileName(entry), '.')) {
	        		relpath=path.substring(8);
	        		//log.info("extension","deploy context "+relpath);
	        		logger.log(Log.LEVEL_INFO,"extension","deploy context "+relpath);
	        		ConfigWebAdmin.updateContext(ci, zis, relpath,false);
	        		contexts.add(relpath);
	        	}
	        	
	        	// applications
	        	if(!entry.isDirectory() && startsWith(path,type,"applications") && !StringUtil.startsWith(fileName(entry), '.')) {
	        		relpath=path.substring(13);
	        		//log.info("extension","deploy context "+relpath);
	        		logger.log(Log.LEVEL_INFO,"extension","deploy application "+relpath);
	        		ConfigWebAdmin.updateApplication(ci, zis, relpath,false);
	        		applications.add(relpath);
	        	}
	        	
	        	
	            zis.closeEntry() ;
	        }
	        
	        //installation successfull
	        
	        ConfigWebAdmin.updateRHExtension(ci,
	        		new RHExtension(id,name,version,
	        		jars.toArray(new String[jars.size()]),
	        		flds.toArray(new String[flds.size()]),
	        		tlds.toArray(new String[tlds.size()]),
	        		contexts.toArray(new String[contexts.size()]),
	        		applications.toArray(new String[applications.size()])));
	        
        }
	    catch(Throwable t){
        	ExceptionUtil.rethrowIfNecessary(t);
	    	// installation failed
	    	
	    	LogUtil.log(logger, Log.LEVEL_ERROR,"extension",t);
			moveToFailedFolder(trgFile);
			return;
	    }
        finally {
        	IOUtil.closeEL(zis);
        }
	}

	private static Manifest toManifest(Config config,InputStream is, Manifest defaultValue) {
		try {
			String cs = config.getResourceCharset();
			String str = IOUtil.toString(is,cs);
			if(StringUtil.isEmpty(str,true)) return defaultValue;
			str=str.trim()+"\n";
			return new Manifest(new ByteArrayInputStream(str.getBytes(cs)));
		}
		catch (Throwable t) {
        	ExceptionUtil.rethrowIfNecessary(t);
			return defaultValue;
		}
	}

	private static boolean startsWith(String path,String type, String name) {
		return StringUtil.startsWithIgnoreCase(path, name+"/") || StringUtil.startsWithIgnoreCase(path, type+"/"+name+"/");
	}

	private static String fileName(ZipEntry entry) {
		String name = entry.getName();
		int index=name.lastIndexOf('/');
		if(index==-1) return name;
		return name.substring(index+1);
	}

	private static void moveToFailedFolder(Resource archive) {
		Resource dir = archive.getParentResource().getRealResource("failed-to-deploy");
		Resource dst = dir.getRealResource(archive.getName());
		dir.mkdirs();
		
		try {
			if(dst.exists()) dst.remove(true);
			ResourceUtil.moveTo(archive, dst,true);
		}
		catch (Throwable t) {
        	ExceptionUtil.rethrowIfNecessary(t);
        }
		
		// TODO Auto-generated method stub
		
	}

	private static String unwrap(String value) {
		if(value==null) return "";
		String res = unwrap(value,'"');
		if(res!=null) return res; // was double quote
		
		return unwrap(value,'\''); // try single quote unwrap, when there is no double quote.
	}
	
	private static String unwrap(String value, char del) {
		value=value.trim();
		if(StringUtil.startsWith(value, del) && StringUtil.endsWith(value, del)) {
			return value.substring(1, value.length()-1);
		}
		return value;
	}
	
}
