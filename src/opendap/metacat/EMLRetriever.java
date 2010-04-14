/////////////////////////////////////////////////////////////////////////////
//
// Copyright (c) 2010 OPeNDAP, Inc.
// Author: James Gallagher <jgallagher@opendap.org>
//
// This library is free software; you can redistribute it and/or
// modify it under the terms of the GNU Lesser General Public
// License as published by the Free Software Foundation; either
// version 2.1 of the License, or (at your option) any later version.
//
// This library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
// Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public
// License along with this library; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
//
// You can contact OPeNDAP, Inc. at PO Box 112, Saunderstown, RI. 02874-0112.
/////////////////////////////////////////////////////////////////////////////

package opendap.metacat;

//import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
/*import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;*/
import java.io.UnsupportedEncodingException;
/*import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.Date;*/
import java.util.Enumeration;

import javax.xml.transform.stream.StreamSource;

import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.XdmNode;

import opendap.xml.Transformer;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class handles the task of getting an EML given a DDX document. It can
 * test the returned document to see if it is well-formed and it can cache the
 * document.
 * 
 * @author jimg
 * 
 */
public class EMLRetriever {

	final static String ddx2emlPath = "ddx2eml-2.0.xsl";
	
	// The EMLCache that holds both the DDXs LMT and the EML XML/text
	private ResponseCache EMLCache;

    // This is the transformer that takes the DDX and returns EML
    private Transformer transformer;

    private static Logger log;

	public EMLRetriever() throws Exception {
		this(LoggerFactory.getLogger(DapIngest.class), true, true, "");
	}

	public EMLRetriever(boolean useCache, boolean saveCache,
			String namePrefix) throws Exception {
		this(LoggerFactory.getLogger(DapIngest.class), useCache,
				saveCache, namePrefix);
	}

	public EMLRetriever(Logger log, boolean useCache, boolean saveCache,
			String namePrefix) throws Exception {
		EMLRetriever.log = log;

		transformer = new Transformer(ddx2emlPath);
		
		// The first parameter to EMLCache() restores the cache from its
		// persistent form and will cause the cache to be written when
		// the DDXCache instance is collected.
		if (useCache)
			EMLCache = new ResponseCache(namePrefix + "EML", useCache, saveCache);
		else
			EMLCache = null;
	}

	/**
	 * Get EML from a DDX URL or from the local cache of previously built
	 * EML documents. This 'main()' builds an instance of DDXRetreiver so that
	 * it can either read the DDX from the net or get it from a cache (other
	 * options not withstanding) but the classes other methods assume that 
	 * the DDX wil be accessed by the caller or a cached EML response is 
	 * being requested.
	 * 
	 * @param args
	 */
	@SuppressWarnings("static-access")
	public static void main(String[] args) {
		EMLRetriever retriever = null;

		// create the command line parser
		CommandLineParser parser = new PosixParser();

		// create the Options
		Options options = new Options();

		// The default action is to read from the net, checking the cache and
		// print the document to standard output.
		options.addOption("r", "read-cache", false, "Read EML from the cache");
		options.addOption("n", "no-cache", false, "Do not cache EMLs. Ignored with -r or -p");
		options.addOption("p", "print-cached", false, "Print all of the cached EMLs");

		options.addOption(OptionBuilder.withLongOpt("cache-name")
						.withDescription( "Use this to set a prefix for the cache name.")
						.hasArg()
						.withArgName("cacheName")
						.create());

		options.addOption(OptionBuilder
						.withLongOpt("ddx-url")
						.withDescription("use this as the DDX URL and build the EML from the associated DDX document")
						.hasArg()
						.withArgName("ddxURL")
						.create());

		try {
			// parse the command line arguments
			CommandLine line = parser.parse(options, args);

			String ddxURL = line.getOptionValue("ddx-url");
			System.out.println("DDX URL: " + ddxURL);

			boolean useCache = !line.hasOption("n");
			String cacheNamePrefix = line.getOptionValue("cache-name");

			retriever = new EMLRetriever(useCache, useCache, cacheNamePrefix);

			if (line.hasOption("r")) {
				System.out.println("EML: " + retriever.getCachedEMLDoc(ddxURL));
			} else if (line.hasOption("p")) {
				Enumeration<String> emls = retriever.EMLCache.getLastVisitedKeys();
				while (emls.hasMoreElements()) {
					ddxURL = emls.nextElement();
					System.out.println("DDX URL: " + ddxURL);
					System.out.println("EML: " + retriever.EMLCache.getCachedResponse(ddxURL));
				}
			} else {
				DDXRetriever ddxSource = new DDXRetriever(true, true, cacheNamePrefix);
				System.out.println("EML: " + retriever.getEML(ddxURL, ddxSource.getDDXDoc(ddxURL)));
			}

			// save the cache if neither the 'no-cache' nor read-cache options
			// were used.
			if (! (line.hasOption("n") && line.hasOption("r")))
				retriever.EMLCache.saveState();

		}
		catch (ParseException exp) {
			System.err.println("Unexpected exception:" + exp.getMessage());
		}

		catch (Exception e) {
			System.err.println("Error : " + e.getMessage());
			e.printStackTrace();
		}
	}

	/**
	 * Get the cache. Use the methods in ResponseCache to get information from
	 * the cache. For this class the cache holds the LMTs and DDX for each URL
	 * (the URLs are the keys).
	 * 
	 * @return The EML cache.
	 */
	public ResponseCache getCache() {
		return EMLCache;
	}

	/**
	 * Simple method to test if the EML will parse. Generally there's no need to
	 * call this but it'll be useful when developing the crawler.
	 * 
	 * @note This method must be called by client code; it is not used by any of
	 *       the methods here.
	 * 
	 * @param ddxString
	 *            The EML to test
	 * @return true if the EML parses, false if the SAX parser throws an
	 *         exception
	 */
	public boolean isWellFormedEML(String emlString) {
		try {
			org.jdom.input.SAXBuilder sb = new org.jdom.input.SAXBuilder();
			@SuppressWarnings("unused")
			org.jdom.Document emlDoc = sb.build(new ByteArrayInputStream(emlString.getBytes()));
		}
		catch (Exception e) {
			log.error("Exception while testing EML: " + e.getLocalizedMessage());
			return false;
		}
		return true;
	}
	
	/**
	 * Build and cache an EML docuemnt using the given DDX document. Use the 
	 * DDX's URL as the key for the cache entry. If caching is not on, ignore
	 * the DDX URL and don't use the cache.
	 * 
	 * @param DDXURL Use this as the key when caching the EML
	 * @param ddxString Build EML from this document
	 * @return
	 * @throws SaxonApiException If the DDX could not be transformed into EML
	 * using the XSLT transform
	 * @throws UnsupportedEncodingException Some issue building the Xdm Node
	 * object needed for the transform.
	 */
	public String getEML(String DDXURL, String ddxString) throws SaxonApiException, UnsupportedEncodingException {
		// Get the EML document
		ByteArrayOutputStream os = new ByteArrayOutputStream();
		XdmNode ddxXdm = null;
		try {
			ddxXdm = transformer.build(new StreamSource(new ByteArrayInputStream(ddxString.getBytes("UTF-8"))));
		}
		catch (SaxonApiException e) {
			log.error("Problem building XdmNode. Message: " + e.getMessage());
			throw e;
		} catch (UnsupportedEncodingException e) {
			log.error("Problem building XdmNode. Message: " + e.getMessage());
			throw e;
		}
		
		try {
			transformer.transform(ddxXdm, os);
		} 
		catch (SaxonApiException e) {
			log.error("Problem with XSLT transform. Message: " + e.getMessage());
			throw e;
		}

		String eml = os.toString();
		
		if (EMLCache != null)
			EMLCache.setCachedResponse(DDXURL, eml);
		
		return eml;
	}
    
	/**
	 * Return the EML document generated using the DDX from the given DDX URL.
	 * This method reads from the EML cache.
	 * 
	 * @param DDXURL The DDX URL is the key used to reference the EML document.
	 * @return The EML in a String.
	 * @throws Exception Thrown if caching is not on.
	 */
	public String getCachedEMLDoc(String DDXURL) throws Exception {
		if (EMLCache == null)
			throw new Exception("Caching is off but I was asked to read from the cache.");
		return EMLCache.getCachedResponse(DDXURL);
	}

	/**
	 * Save the EML cache.
	 * 
	 * @throws Exception Thrown if caching is not on.
	 */
	public void saveEMLCache() throws Exception {
		if (EMLCache == null)
			throw new Exception("Caching is off but I was asked to save the cache.");
		EMLCache.saveState();
	}
}
