/////////////////////////////////////////////////////////////////////////////
// This file is part of the "OPeNDAP 4 Data Server (aka Hyrex)" project.
//
//
// Copyright (c) 2006 OPeNDAP, Inc.
// Author: Nathan David Potter  <ndp@opendap.org>
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

package opendap.coreServlet;

import thredds.catalog.InvDatasetScan;
import thredds.servlet.DataRootHandler;
import thredds.servlet.HtmlWriter;
import thredds.servlet.ServletUtil;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.File;
import java.util.StringTokenizer;
import java.util.Date;
import java.util.concurrent.locks.ReentrantLock;

/**
 * This servlet provides the dispatching for all OPeNDAP requests.
 * <p/>
 * <p>This server will respond to both HTTP GET and POST requests. The GET dispatching is
 * done in this class, and the POST dispatching (which is in fact the SOAP inrterface)
 * is done in <code>SOAPRequestDispatcher</code></p>
 * <p/>
 * <p/>
 * <p>This server is built designed so that the actual handling of the dispatch is done
 * through code that is identified at run time through the web.xml configuration of the
 * servlet. In particular the HTTP GET request are handled by a class the implements the
 * OpendapHttpDispatchHandler interface. The SOAP requests (via HTTP POST) are handled by a
 * class the implements the OpendapSOAPDispatchHandler interface.<p>
 * <p/>
 * <p>The web.xml file used to configure this servlet must contain servlet parameters identifying
 * an implmentation clas for both these interfaces.</p>
 * <p/>
 * <p/>
 * <p/>
 */
public class DispatchServlet extends HttpServlet {




    /**
     * ************************************************************************
     * Used for thread syncronization.
     *
     * @serial
     */
    private static final Object syncLock = new Object();


    /**
     * ************************************************************************
     * Count "hits" on the server...
     *
     * @serial
     */
    private int HitCounter = 0;


    private long threddsInitTime;
    private ReentrantLock threddsUpdateLock;


    private OpendapHttpDispatchHandler odh = null;
    private OpendapSoapDispatchHandler sdh = null;


    protected DataRootHandler dataRootHandler;
    protected org.slf4j.Logger log;


    protected String getDocsPath() {
        return "docs/";
    }


    /**
     * ************************************************************************
     * Intitializes the servlet. Init (at this time) basically sets up
     * the object opendap.util.Debug from the debuggery flags in the
     * servlet InitParameters. The Debug object can be referenced (with
     * impunity) from any of the dods code...
     *
     * @throws javax.servlet.ServletException
     */
    public void init() throws ServletException {

        super.init();
        initDebug();
        initLogging();

        ReqInfo.init();
        SOAPRequestDispatcher.init();

        // init logging
        PerfLog.logServerSetup(this.getClass().getName() + "init() start");

        PersistentContentHandler.installInitialContent(this);


        String className = getInitParameter("OpendapHttpDispatchHandlerImplementation");
        if (className == null)
            throw new ServletException("Missing servlet parameter \"OpendapHttpDispatchHandlerImplementation\"." +
                    "A class that implements the opendap.coreServlet.OpendapHttpDispatchHandler interface must" +
                    "be identified in this (missing) servlet parameter.");


        log.info("OpendapHttpDispatchHandlerImplementation is " + className);

        try {
            Class classDefinition = Class.forName(className);
            odh = (OpendapHttpDispatchHandler) classDefinition.newInstance();
        } catch (InstantiationException e) {
            throw new ServletException("Cannot instantiate class: " + className, e);
        } catch (IllegalAccessException e) {
            throw new ServletException("Cannot access class: " + className, e);
        } catch (ClassNotFoundException e) {
            throw new ServletException("Cannot find class: " + className, e);
        }


        odh.init(this);


        initTHREDDS(ServletUtil.getContextPath(this), ServletUtil.getContentPath(this));




        className = getInitParameter("OpendapSoapDispatchHandlerImplementation");
        if (className == null)
            throw new ServletException("Missing servlet parameter \"OpendapSoapDispatchHandlerImplementation\"." +
                    "A class that implements the opendap.coreServlet.OpendapSoapDispatchHandler interface must" +
                    "be identified in this (missing) servlet parameter.");

        log.info("OpendapSoapDispatchHandlerImplementation is " + className);

        try {
            Class classDefinition = Class.forName(className);
            sdh = (OpendapSoapDispatchHandler) classDefinition.newInstance();
        } catch (InstantiationException e) {
            throw new ServletException("Cannot instantiate class: " + className, e);
        } catch (IllegalAccessException e) {
            throw new ServletException("Cannot access class: " + className, e);
        } catch (ClassNotFoundException e) {
            throw new ServletException("Cannot find class: " + className, e);
        }

        sdh.init(this,dataRootHandler);



        log.info("init() complete.  ");


    }
    /***************************************************************************/


    /**
     * ************************************************************************
     * <p/>
     * Process the DebugOn initParameter and turn on the requested debugging
     * states in the Debug object.
     */
    private void initDebug() {
        // Turn on debugging.
        String debugOn = getInitParameter("DebugOn");
        if (debugOn != null) {
            System.out.println("** DebugOn **");
            StringTokenizer toker = new StringTokenizer(debugOn);
            while (toker.hasMoreTokens()) Debug.set(toker.nextToken(), true);
        }

    }

    /**
     * ***********************************************************************
     */


    private void initLogging() {
        thredds.servlet.ServletUtil.initLogging(this);
        PerfLog.initLogging(this);
        log = org.slf4j.LoggerFactory.getLogger(getClass());

    }


    /**
     * ************************************************************************
     * <p/>
     * Initialize the THREDDS environment so that THREDDS works correctly.
     *
     * @param contextPath The context path for this servlet.
     * @param contentPath The path to the peristemnt configuration content for this servlet.
     */
    private void initTHREDDS(String contextPath, String contentPath) {

        thredds.servlet.ServletUtil.initDebugging(this); // read debug flags


        InvDatasetScan.setContext(contextPath); // This gets your context path
        // from web.xml above.

        // This allows you to specify which servlet handles catalog requests.
        // We set it to "/catalog". Is "/ts" the servlet path for you? If so,
        // set this to "/ts".
        // If you use the default servlet for everything (path mapping of
        // "/*" in web.xml). set it to the empty string.
        InvDatasetScan.setCatalogServletName("/" + getServletName());

        // handles all catalogs, including ones with DatasetScan elements,
        // ie dynamic
        DataRootHandler.init(contentPath, contextPath);
        dataRootHandler = DataRootHandler.getInstance();
        try {
            dataRootHandler.initCatalog("catalog.xml");
            //dataRootHandler.initCatalog( "extraCatalog.xml" );
        }
        catch (Throwable e) {
            log.error("Error initializing catalog: " + e.getMessage(), e);
        }

        //this.makeDebugActions();
        //dataRootHandler.makeDebugActions();
        //DatasetHandler.makeDebugActions();

        HtmlWriter.init(
                contextPath,                              // context path
                "Hyrax Data Server",                      // Name of Webb Application
                odh.getVersionStringForTHREDDSCatalog(),  // Version
                this.getDocsPath(),                       // docs path
                "docs/css/thredds.css",                   // userCssPath
                "docs/images/folder.gif",                 // context Logo
                "Context Logo",                           // Alternate text for context logo
                "docs/images/logo.gif",                   // Institute Logo path
                "OPeNDAP Inc.",                           // Alternate text for Institute logo
                "docs/images/sml-folder.png",             // Folder Image
                "This is a collection  "                  // Alternate text for folder image
        );

        threddsUpdateLock = new ReentrantLock(true);
        threddsInitTime = new Date().getTime();

        log.info("THREDDS initialized ");

    }
    /***************************************************************************/


    /**
     * ************************************************************************
     * <p/>
     * In this (default) implementation of the getServerName() method we just get
     * the name of the servlet and pass it back. If something different is
     * required, override this method when implementing the writeDDS() and
     * getXDODSServerVersion() methods.
     * <p/>
     * This is typically used by the getINFO() method to figure out if there is
     * information specific to this server residing in the info directory that
     * needs to be returned to the client as part of the .info response.
     *
     * @return A string containing the name of the servlet class that is running.
     */
    public String getServerName() {

        // Ascertain the name of this server.
        String servletName = this.getClass().getName();

        return (servletName);
    }


    /**
     * Gets the last modified date of the requested resource. Because the data handler is really
     * the only entity capable of determining the last modified dat the job is passed  through to it.
     *
     * @param req The current request
     * @return Returns the time the HttpServletRequest object was last modified, in milliseconds
     *         since midnight January 1, 1970 GMT
     */
    protected long getLastModified(HttpServletRequest req) {
        return odh.getLastModified(req);
    }


    /**
     * Performs dispatching for "special" server requests. This server supports several diagnositic responses:
     * <ui>
     * <li> version - returns the OPeNDAP version document (XML) </li>
     * <li> help - returns the help page for Hyrax  </li>
     * <li> systemproperties - returns an html document describing the state of the "system" </li>
     * <li> debug -   </li>
     * <li> status -    </li>
     * <li> contents.html -  Returns the OPeNDAP directory view of a collection (as an HTML document)</li>
     * <li> catalog.html - Returns the THREDDS catalog view of a collection (as an HTML document)</li>
     * <li> catalog.xml - Returns the THREDDS catalog of a collection (as an XML document)</li>
     * </ui>
     *
     * @param request  The current request
     * @param response The response to which we write.
     * @return true if the request was handled as a special request, false otherwise.
     * @throws Exception When things go awry.
     */
    public boolean specialRequestDispatch(HttpServletRequest request,
                                          HttpServletResponse response) throws Exception {

        String dataSource = ReqInfo.getDataSource(request);
        String dataSetName = ReqInfo.getDataSetName(request);
        String requestSuffix = ReqInfo.getRequestSuffix(request);


        boolean threddsCatalog = false;
        boolean specialRequest = false;

        if (dataSource != null) {

            if (        // Version Response?
                    dataSource.equalsIgnoreCase("/version")
                    ) {
                odh.sendVersion(request, response);
                log.debug("Sent Version Response");
                specialRequest = true;

            } else if ( // Help Response?
                    dataSource.equalsIgnoreCase("/help") ||
                            dataSource.equalsIgnoreCase("/help/") ||
                            ((requestSuffix != null) &&
                                    requestSuffix.equalsIgnoreCase("help"))
                    ) {
                odh.sendHelpPage(request, response);
                log.info("Sent Help Page");
                specialRequest = true;

            } else if ( // System Properties Response?
                //Debug.isSet("SystemProperties") &&

                    dataSource.equalsIgnoreCase("/systemproperties")
                    ) {
                Util.sendSystemProperties(request, response, odh);
                log.info("Sent System Properties");
                specialRequest = true;

            } else if (    // Debug response?
                    Debug.isSet("DebugInterface") &&
                            dataSource.equals("/debug") &&
                            (requestSuffix != null) &&
                            requestSuffix.equals("")) {

                DebugHandler.doDebug(this, request, response, odh, this.getServletConfig());
                log.info("Sent Debug Response");
                specialRequest = true;

            } else if (  // Status Response?

                    dataSource.equalsIgnoreCase("/status")
                    ) {
                doGetStatus(request, response);
                log.info("Sent Status");
                specialRequest = true;

            } else if (dataSetName != null) {


                if (   //  Directory response?
                        dataSetName.equalsIgnoreCase("contents") &&
                                requestSuffix != null &&
                                requestSuffix.equalsIgnoreCase("html")) {


                    odh.sendDir(request, response);

                    log.info("Sent Contents (aka OPeNDAP directory).");

                    specialRequest = true;

                } else if ( //  THREDDS Catalog ?
                        dataSetName.equalsIgnoreCase("catalog") &&
                        requestSuffix != null &&
                        (requestSuffix.equalsIgnoreCase("html") || requestSuffix.equalsIgnoreCase("xml"))
                        ) {

                    threddsCatalog = getThreddsCatalog(request, response);
                }
            }
        }


        if(specialRequest && !threddsCatalog)
            PerfLog.logServerAccessEnd(HttpServletResponse.SC_OK, -1, "HyraxAccess");

        return specialRequest || threddsCatalog;

    }


    /**
     * Performs dispatching for OPeNDAP data requests. The OPeNDAP response suite consists of:
     * <ui>
     * <li>dds - The OPeNDAP Data Description Service document for the requested dataset. </li>
     * <li>das - The OPeNDAP Data Attribute Service document for the requested dataset. </li>
     * <li>ddx - The OPeNDAP DDX document, an XML document that combines the DDS and the DAS. </li>
     * <li>dods - The OPeNDAP DAP2 data service. Returns data to the user as described in
     * the DAP2 specification </li>
     * <li>ascii - The requested data as columns of ASCII values. </li>
     * <li>info - An HTML document providing a easy to read view of the DDS and DAS information. </li>
     * <li>html - The HTML request form from which users can choose wich components of a dataset they wish
     * to retrieve. </li>
     * </ui>
     *
     * @param request  .
     * @param response .
     * @return true if the request was handled as an OPeNDAP service request, false otherwise.
     * @throws Exception .
     */
    public boolean dataSetDispatch(HttpServletRequest request,
                                   HttpServletResponse response) throws Exception {


        String dataSource = ReqInfo.getDataSource(request);
        String requestSuffix = ReqInfo.getRequestSuffix(request);

        DataSourceInfo dsi = odh.getDataSourceInfo(dataSource);

        boolean isDataRequest = false;

        if (dsi.sourceExists()) {

            if (requestSuffix != null && dsi.isDataset()) {

                if ( // DDS Response?
                        requestSuffix.equalsIgnoreCase("dds")
                        ) {
                    odh.sendDDS(request, response);
                    isDataRequest = true;
                    log.info("Sent DDS");
                    PerfLog.logServerAccessEnd(HttpServletResponse.SC_OK, -1, "HyraxAccess");

                } else if ( // DAS Response?
                        requestSuffix.equalsIgnoreCase("das")
                        ) {
                    odh.sendDAS(request, response);
                    isDataRequest = true;
                    log.info("Sent DAS");
                    PerfLog.logServerAccessEnd(HttpServletResponse.SC_OK, -1, "HyraxAccess");

                } else if (  // DDX Response?
                        requestSuffix.equalsIgnoreCase("ddx")
                        ) {
                    odh.sendDDX(request, response);
                    isDataRequest = true;
                    log.info("Sent DDX");
                    PerfLog.logServerAccessEnd(HttpServletResponse.SC_OK, -1, "HyraxAccess");

                } else if ( // Blob Response?
                        requestSuffix.equalsIgnoreCase("blob")
                        ) {
                    //doGetBLOB(request, response, rs);
                    badURL(request, response);
                    isDataRequest = true;
                    log.info("Sent BAD URL Response because they asked for a Blob. Bad User!");
                    PerfLog.logServerAccessEnd(HttpServletResponse.SC_OK, -1, "HyraxAccess");

                } else if ( // DAP2 (aka .dods) Response?
                        requestSuffix.equalsIgnoreCase("dods")
                        ) {
                    odh.sendDAP2Data(request, response);
                    isDataRequest = true;
                    log.info("Sent DAP2 Data");
                    PerfLog.logServerAccessEnd(HttpServletResponse.SC_OK, -1, "HyraxAccess");

                } else if (  // ASCII Data Response.
                        requestSuffix.equalsIgnoreCase("asc") ||
                                requestSuffix.equalsIgnoreCase("ascii")
                        ) {
                    odh.sendASCII(request, response);
                    isDataRequest = true;
                    log.info("Sent ASCII");
                    PerfLog.logServerAccessEnd(HttpServletResponse.SC_OK, -1, "HyraxAccess");

                } else if (  // Info Response?
                        requestSuffix.equalsIgnoreCase("info")
                        ) {
                    odh.sendInfo(request, response);
                    isDataRequest = true;
                    log.info("Sent Info");
                    PerfLog.logServerAccessEnd(HttpServletResponse.SC_OK, -1, "HyraxAccess");

                } else
                if (  //HTML Request Form (aka The Interface From Hell) Response?
                        requestSuffix.equalsIgnoreCase("html") ||
                                requestSuffix.equalsIgnoreCase("htm")
                        ) {
                    odh.sendHTMLRequestForm(request, response);
                    isDataRequest = true;
                    log.info("Sent HTML Request Form");
                    PerfLog.logServerAccessEnd(HttpServletResponse.SC_OK, -1, "HyraxAccess");


                } else if (requestSuffix.equals("")) {
                    badURL(request, response);
                    isDataRequest = true;
                    log.info("Sent BAD URL (missing Suffix)");
                    PerfLog.logServerAccessEnd(HttpServletResponse.SC_NOT_FOUND, -1, "HyraxAccess");

                } else {
                    badURL(request, response);
                    isDataRequest = true;
                    log.info("Sent BAD URL - not an OPeNDAP request suffix.");
                    PerfLog.logServerAccessEnd(HttpServletResponse.SC_NOT_FOUND, -1, "HyraxAccess");
                }

            }
        }


        return isDataRequest;
    }


    /**
     * Performs dispatching for file requests. If a request is not for a special service or an OPeNDAP service
     * then we attempt to resolve the request to a "file" located within the context of the data handler and
     * return it's contents.
     *
     * @param request  .
     * @param response .
     * @return true if the request was serviced as a file request, false otherwise.
     * @throws Exception .
     */
    public boolean fileDispatch(HttpServletRequest request,
                                HttpServletResponse response) throws Exception {


        String fullSourceName = ReqInfo.getFullSourceName(request);

        DataSourceInfo dsi = odh.getDataSourceInfo(fullSourceName);

        boolean isFileResponse = false;

        if (dsi.sourceExists()) {
            if (dsi.isCollection()) {
                if (odh.useOpendapDirectoryView()) {
                    odh.sendDir(request, response);
                    PerfLog.logServerAccessEnd(HttpServletResponse.SC_OK, -1, "HyraxAccess");
                } else {
                    getThreddsCatalog(request, response);
                }

            } else {

                if (!dsi.isDataset() || odh.allowDirectDataSourceAccess()) {
                    odh.sendFile(request, response);
                    PerfLog.logServerAccessEnd(HttpServletResponse.SC_OK, -1, "HyraxAccess");
                } else {
                    sendDirectAccessDenied(request, response);
                    PerfLog.logServerAccessEnd(HttpServletResponse.SC_FORBIDDEN, -1, "HyraxAccess");
                }
            }
            isFileResponse = true;

        }

        return isFileResponse;

    }


    /**
     * ***********************************************************************
     * Handles incoming requests from clients. Parses the request and determines
     * what kind of OPeNDAP response the cleint is requesting. If the request is
     * understood, then the appropriate handler method is called, otherwise
     * an error is returned to the client.
     * <p/>
     * This method is the entry point for <code>OLFS</code>. It uses
     * the methods <code>processOpendapURL</code> to extract the OPeNDAP URL
     * information from the incoming client request. This OPeNDAP URL information
     * is cached and made accessible through get and set methods.
     * <p/>
     * After  <code>processOpendapURL</code> is called <code>loadIniFile()</code>
     * is called to load configuration information from a .ini file,
     * <p/>
     * If the standard behaviour of the servlet (extracting the OPeNDAP URL
     * information from the client request, or loading the .ini file) then
     * you should overload <code>processOpendapURL</code> and <code>loadIniFile()
     * </code>. <b> We don't recommend overloading <code>doGet()</code> beacuse
     * the logic contained there may change in our core and cause your server
     * to behave unpredictably when future releases are installed.</b>
     *
     * @param request  The client's <code> HttpServletRequest</code> request
     *                 object.
     * @param response The server's <code> HttpServletResponse</code> response
     *                 object.
     * @see ReqInfo
     */
    public void doGet(HttpServletRequest request,
                      HttpServletResponse response)
            throws IOException, ServletException {

        PerfLog.logServerAccessStart(request, "HyraxAccess");


        try {

            if (Debug.isSet("probeRequest"))
                Util.probeRequest(System.out, this, request, getServletContext(), getServletConfig());


            synchronized (syncLock) {
                long reqno = HitCounter++;
                log.debug(Util.showRequest(request, reqno));

                log.info("Requested dataSource: '" + ReqInfo.getDataSource(request) +
                        "' suffix: '" + ReqInfo.getRequestSuffix(request) +
                        "' CE: '" + ReqInfo.getConstraintExpression(request) + "'");
            } // synch


            if (!specialRequestDispatch(request, response)) {
                if (!dataSetDispatch(request, response)) {
                    if (!fileDispatch(request, response)) {
                        sendResourceNotFound(request, response);
                        log.info("Sent Resource Not Found (404) - nothing left to check.");
                        PerfLog.logServerAccessEnd(HttpServletResponse.SC_NOT_FOUND, -1, "HyraxAccess");
                    }

                }
            }

        } catch (Throwable e) {
            OPeNDAPException.anyExceptionHandler(e, response);
        }


    }
    //**************************************************************************


    /**
     * This helper function makes sure that an empty request path dosn't bone the THREDDS code
     * for no good reason. IN other words the top level catalog gets returned even when the URL doesn't
     * end in a "/"
     *
     * @param req Client Request
     * @param res Server Response
     * @return True if a THREDDS catalog.xml or catalog.html was returned to the client
     * @throws Exception When things go poorly.
     */
    private boolean getThreddsCatalog(HttpServletRequest req, HttpServletResponse res)
            throws Exception {

        ServletUtil.logServerAccessSetup(req);

        if ((req.getPathInfo() == null)) {
            String newPath = req.getRequestURL() + "/";
            res.sendRedirect(newPath);
            log.info("Sent THREDDS redirect to avoid a null valued return to request.getPathInfo().");
            PerfLog.logServerAccessEnd(HttpServletResponse.SC_MOVED_TEMPORARILY, -1, "HyraxAccess");
            return true;
        }


        threddsUpdateLock.lock();
        try {
            String masterCatalog = ServletUtil.getContentPath(this) + "catalog.xml";
            File f = new File(masterCatalog);
            if (f.lastModified() > threddsInitTime) {
                threddsInitTime = f.lastModified();
                log.info("getThreddsCatalog(): Reinitializing THREDDS catalogs.  ");
                dataRootHandler.reinit();
                dataRootHandler.initCatalog("catalog.xml");
                log.info("getThreddsCatalog(): THREDDS has been reinitialized.  ");
            }
        }
        finally {
            threddsUpdateLock.unlock();
        }

        boolean isCatalog = dataRootHandler.processReqForCatalog(req, res);

        if (isCatalog) {
            log.info("Sent THREDDS catalog (xml/html)");
            PerfLog.logServerAccessEnd(HttpServletResponse.SC_OK, -1, "HyraxAccess");
        }

        return isCatalog;

    }


    /**
     * Default handler for OPeNDAP status requests; not publically available,
     * used only for debugging
     *
     * @param request  The client's <code> HttpServletRequest</code> request
     *                 object.
     * @param response The server's <code> HttpServletResponse</code> response
     *                 object.
     * @throws IOException If unablde to right to the response.
     */
    public void doGetStatus(HttpServletRequest request,
                            HttpServletResponse response)
            throws Exception {


        response.setHeader("XDODS-Server", odh.getXDODSServerVersion(request));
        response.setHeader("XOPeNDAP-Server", odh.getXOPeNDAPServerVersion(request));
        response.setHeader("XDAP", odh.getXDAPVersion(request));
        response.setContentType("text/html");
        response.setHeader("Content-Description", "dods_status");
        response.setStatus(HttpServletResponse.SC_OK);

        PrintWriter pw = new PrintWriter(response.getOutputStream());
        pw.println("<title>Server Status</title>");
        pw.println("<body><ul>");
        printStatus(pw);
        pw.println("</ul></body>");
        pw.flush();

    }


    // to be overridden by servers that implement status report
    protected void printStatus(PrintWriter os) throws IOException {
        os.println("<h2>Number of Requests Received = " + HitCounter + "</h2>");
    }


    /**
     * Sends an html document to the client explaining that they have used a
     * poorly formed URL and then the help page...
     *
     * @param request  The client's <code> HttpServletRequest</code> request
     *                 object.
     * @param response The server's <code> HttpServletResponse</code> response
     *                 object.
     * @throws IOException If it can't right the response.
     */
    public void badURL(HttpServletRequest request,
                       HttpServletResponse response)
            throws Exception {

        log.debug("Sending Bad URL Page.");

        response.setContentType("text/html");
        response.setHeader("XDODS-Server", odh.getXDODSServerVersion(request));
        response.setHeader("XOPeNDAP-Server", odh.getXOPeNDAPServerVersion(request));
        response.setHeader("XDAP", odh.getXDAPVersion(request));
        response.setHeader("Content-Description", "BadURL");
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);

        // Commented because of a bug in the OPeNDAP C++ stuff...
        //response.setHeader("Content-Encoding", "plain");

        PrintWriter pw = new PrintWriter(new OutputStreamWriter(response.getOutputStream()));

        pw.println("<h3>Error in URL</h3>");
        pw.println("<p>The URL extension did not match any that are known by this");
        pw.println("server. Here is a list of the five extensions that are be recognized by");
        pw.println("all OPeNDAP servers:</p>");
        pw.println("<ui>");
        pw.println("    <li>ddx</li>");
        pw.println("    <li>dds</li>");
        pw.println("    <li>das</li>");
        pw.println("    <li>dods</li>");
        pw.println("    <li>info</li>");
        pw.println("    <li>html</li>");
        pw.println("    <li>ascii</li>");
        pw.println("</ui>");
        pw.println("<p>If you think that the server is broken (that the URL you");
        pw.println("submitted should have worked), then please contact the");
        pw.println("OPeNDAP user support coordinator at: ");
        pw.println("<a href=\"mailto:support@unidata.ucar.edu\">support@unidata.ucar.edu</a></p>");

        pw.flush();


    }
    /***************************************************************************/


    /**
     * @param request  .
     * @param response .
     * @throws IOException
     * @throws ServletException
     */
    @Override
    public void doPost(HttpServletRequest request,
                       HttpServletResponse response)
            throws IOException, ServletException {


        SOAPRequestDispatcher.doPost(request, response, odh, sdh);
    }


    public void destroy() {

        odh.destroy();
        super.destroy();
    }


    private void sendResourceNotFound(HttpServletRequest request, HttpServletResponse response) throws Exception {

        response.setContentType("text/html");
        response.setHeader("XDODS-Server", odh.getXDODSServerVersion(request));
        response.setHeader("XOPeNDAP-Server", odh.getXOPeNDAPServerVersion(request));
        response.setHeader("XDAP", odh.getXDAPVersion(request));
        response.setHeader("Content-Description", "BadURL");
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);

        PrintWriter pw = new PrintWriter(new OutputStreamWriter(response.getOutputStream()));

        String topLevel = request.getRequestURL().substring(0, request.getRequestURL().lastIndexOf(request.getPathInfo()));

        pw.println("<h2>Resource Not Found</h2>");
        pw.println("<p>The URL <i>'" + request.getRequestURL() + "'</i> does not describe a resource that can be found on this server.</p>");
        pw.println("<p>If you would like to start at the top level of this server, go here:</p>");
        pw.println("<p><a href='" + topLevel + "'>" + topLevel + "</a></p>");
        pw.println("<p>If you think that the server is broken (that the URL you");
        pw.println("submitted should have worked), then please contact the");
        pw.println("OPeNDAP user support coordinator at: ");
        pw.println("<a href=\"mailto:support@unidata.ucar.edu\">support@unidata.ucar.edu</a></p>");

        pw.flush();


    }


    private void sendDirectAccessDenied(HttpServletRequest request, HttpServletResponse response) throws Exception {

        response.setContentType("text/html");
        response.setHeader("XDODS-Server", odh.getXDODSServerVersion(request));
        response.setHeader("XOPeNDAP-Server", odh.getXOPeNDAPServerVersion(request));
        response.setHeader("XDAP", odh.getXDAPVersion(request));
        response.setHeader("Content-Description", "BadURL");
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);

        PrintWriter pw = new PrintWriter(new OutputStreamWriter(response.getOutputStream()));

        String topLevel = request.getRequestURL().substring(0, request.getRequestURL().lastIndexOf(request.getPathInfo()));

        pw.println("<h2>ACCESS DENIED</h2>");
        pw.println("<p>The URL <i>'" + request.getRequestURL() + "'</i> references a data source directly. </p>" +
                "<p>You must use the OPeNDAP request interface to get data from the data source.</p>");


        pw.println("<p>If you would like to start at the top level of this server, go here:</p>");
        pw.println("<p><a href='" + topLevel + "'>" + topLevel + "</a></p>");
        pw.println("<p>If you think that the server is broken (that the URL you");
        pw.println("submitted should have worked), then please contact the");
        pw.println("OPeNDAP user support coordinator at: ");
        pw.println("<a href=\"mailto:support@unidata.ucar.edu\">support@unidata.ucar.edu</a></p>");

        pw.flush();


    }


}
