/*
 * /////////////////////////////////////////////////////////////////////////////
 * // This file is part of the "Hyrax Data Server" project.
 * //
 * //
 * // Copyright (c) 2013 OPeNDAP, Inc.
 * // Author: Nathan David Potter  <ndp@opendap.org>
 * //
 * // This library is free software; you can redistribute it and/or
 * // modify it under the terms of the GNU Lesser General Public
 * // License as published by the Free Software Foundation; either
 * // version 2.1 of the License, or (at your option) any later version.
 * //
 * // This library is distributed in the hope that it will be useful,
 * // but WITHOUT ANY WARRANTY; without even the implied warranty of
 * // MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * // Lesser General Public License for more details.
 * //
 * // You should have received a copy of the GNU Lesser General Public
 * // License along with this library; if not, write to the Free Software
 * // Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301
 * //
 * // You can contact OPeNDAP, Inc. at PO Box 112, Saunderstown, RI. 02874-0112.
 * /////////////////////////////////////////////////////////////////////////////
 */

package opendap.coreServlet;


import opendap.bes.BESManager;
import opendap.auth.AuthenticationControls;
import opendap.bes.VersionDispatchHandler;
import opendap.bes.dap2Responders.BesApi;
import opendap.http.error.NotFound;
import opendap.logging.LogUtil;
import opendap.logging.Timer;
import opendap.logging.Procedure;
import opendap.ncml.NcmlDatasetDispatcher;
import opendap.threddsHandler.StaticCatalogDispatch;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jdom.input.SAXBuilder;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Date;
import java.util.StringTokenizer;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This servlet provides the dispatching for all OPeNDAP requests.
 * <p/>
 * <p>This server will respond to both HTTP GET and POST requests.
 * activities are handled by ordered collections of DispatchHandlers.
 * <p/>
 * <p/>
 * <p>This server is designed so that the dispatch activities are handled by
 * ordered collections of DispatchHandlers are identified at run time through
 * the olfs.xml configuration file. The olfs.xml file is identified in the
 * servlets web.xml file. The olfs.xml file is typically located in
 * $CATALINE_HOME/content/opendap.
 *
 * <p/>
 * <p>The web.xml file used to configure this servlet must contain servlet parameters identifying
 * the location of the olfs.xml file.</p>
 * <p/>
 * <p/>
 * <p/>
 */
public class DispatchServlet extends HttpServlet {


    /**
     * ************************************************************************
     * A thread safe hit counter
     *
     * @serial
     */
    private AtomicInteger reqNumber;



    private Vector<DispatchHandler> httpGetDispatchHandlers;
    private Vector<DispatchHandler> httpPostDispatchHandlers;

    private OpendapHttpDispatchHandler odh = null;
    // private ThreddsHandler tdh = null;
    private org.slf4j.Logger log;

    private Document configDoc;

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

        // Timer.enable();


        RequestCache.openThreadCache();

        reqNumber = new AtomicInteger(0);

        log.debug("init() start");

        /*
        String xslTransformerFactoryImpl = "com.icl.saxon.TransformerFactoryImpl";
        String xslTransformerFactoryProperty = "javax.xml.transform.TransformerFactory";

        log.info("init(): Setting System Property " +
                xslTransformerFactoryProperty +
                "="+xslTransformerFactoryImpl);
        System.setProperty(xslTransformerFactoryProperty,xslTransformerFactoryImpl);
        */



        httpGetDispatchHandlers = new Vector<>();
        //Vector<Element> httpGetHandlerConfigs = new Vector<>();
        httpPostDispatchHandlers = new Vector<>();
        //Vector<Element> httpPostHandlerConfig = new Vector<>();

        // init logging
        LogUtil.logServerStartup("init()");
        log.info("init() start.");

        String configFile = getInitParameter("ConfigFileName");
        if (configFile == null) {
            String msg = "Servlet configuration must include a parameter called 'ConfigFileName' whose value" +
                    "is the name of the OLFS configuration file!\n";
            System.err.println(msg);
            throw new ServletException(msg);
        }

        PersistentConfigurationHandler.installDefaultConfiguration(this,configFile);

        loadConfig(configFile);

        Element config = configDoc.getRootElement();

        boolean enablePost = false;
        Element postConfig = config.getChild("HttpPost");
        if(postConfig!=null){
            String enabled = postConfig.getAttributeValue("enabled");
            if(enabled.equalsIgnoreCase("true"))
                enablePost = true;
        }


        Element timer = config.getChild("Timer");
        if(timer!=null){
            String enabled = timer.getAttributeValue("enabled");
            if(enabled!=null && enabled.equalsIgnoreCase("true")){
                Timer.enable();
            }
        }

        log.info("init() - Timer is {}",Timer.isEnabled()?"ENABLED":"DISABLED");



        initBesManager();

        initAuthenticationControls();

        try {
            loadHyraxServiceHandlers(httpGetDispatchHandlers, config);
            if(enablePost){
                opendap.bes.BesDapDispatcher bdd = new opendap.bes.BesDapDispatcher();
                bdd.init(this,config);
                httpPostDispatchHandlers.add(bdd);
            }
        }
        catch (Exception e){
            throw new ServletException(e);
        }
        log.info("init() complete.");
        RequestCache.closeThreadCache();
    }



    private void initAuthenticationControls()  {
        Element authControlElem = configDoc.getRootElement().getChild(AuthenticationControls.CONFIG_ELEMENT);
        AuthenticationControls.init(authControlElem,getServletContext().getContextPath());
    }


    /**
     * Loads the configuration file specified in the servlet parameter
     * ConfigFileName.
     *
     * @throws ServletException When the file is missing, unreadable, or fails
     *                          to parse (as an XML document).
     */
    private void loadConfig(String confFileName) throws ServletException {

        String filename = Scrub.fileName(ServletUtil.getConfigPath(this) + confFileName);

        log.debug("Loading Configuration File: " + filename);
        try {

            File confFile = new File(filename);
            FileInputStream fis = new FileInputStream(confFile);

            try {
                // Parse the XML doc into a Document object.
                SAXBuilder sb = new SAXBuilder();
                configDoc = sb.build(fis);
            }
            finally {
            	fis.close();
            }

        } catch (FileNotFoundException e) {
            String msg = "OLFS configuration file \"" + filename + "\" cannot be found.";
            log.error(msg);
            throw new ServletException(msg, e);
        } catch (IOException e) {
            String msg = "OLFS configuration file \"" + filename + "\" is not readable.";
            log.error(msg);
            throw new ServletException(msg, e);
        } catch (JDOMException e) {
            String msg = "OLFS configuration file \"" + filename + "\" cannot be parsed.";
            log.error(msg);
            throw new ServletException(msg, e);
        }

        log.debug("Configuration loaded and parsed.");

    }


    private void initBesManager() throws ServletException {
        Element besManagerElement = configDoc.getRootElement().getChild("BESManager");
        if(besManagerElement ==  null){
            String msg = "Invalid configuration. Missing required 'BESManager' element. DispatchServlet FAILED to init()!";
            log.error(msg);
            throw new ServletException(msg);

        }
        BESManager besManager  = new BESManager();
        try {
            besManager.init(besManagerElement);
        }
        catch(Exception e){
            throw new ServletException(e);
        }
    }


    /**
     *             <Handler className="opendap.bes.VersionDispatchHandler" />
     *
     *             <!-- Bot Blocker
     *                - This handler can be used to block access from specific IP addresses
     *                - and by a range of IP addresses using a regular expression.
     *               -->
     *             <!-- <Handler className="opendap.coreServlet.BotBlocker"> -->
     *                 <!-- <IpAddress>127.0.0.1</IpAddress> -->
     *                 <!-- This matches all IPv4 addresses, work yours out from here.... -->
     *                 <!-- <IpMatch>[012]?\d?\d\.[012]?\d?\d\.[012]?\d?\d\.[012]?\d?\d</IpMatch> -->
     *                 <!-- Any IP starting with 65.55 (MSN bots the don't respect robots.txt  -->
     *                 <!-- <IpMatch>65\.55\.[012]?\d?\d\.[012]?\d?\d</IpMatch>   -->
     *             <!-- </Handler>  -->
     *             <Handler className="opendap.ncml.NcmlDatasetDispatcher" />
     *             <Handler className="opendap.threddsHandler.StaticCatalogDispatch">
     *                 <prefix>thredds</prefix>
     *                 <useMemoryCache>true</useMemoryCache>
     *             </Handler>
     *             <Handler className="opendap.gateway.DispatchHandler">
     *                 <prefix>gateway</prefix>
     *                 <UseDAP2ResourceUrlResponse />
     *             </Handler>
     *             <Handler className="opendap.bes.BesDapDispatcher" >
     *                 <!-- AllowDirectDataSourceAccess
     *                   - If this element is present then the server will allow users to request
     *                   - the data source (file) directly. For example a user could just get the
     *                   - underlying NetCDF files located on the server without using the OPeNDAP
     *                   - request interface.
     *                   -->
     *                 <!-- AllowDirectDataSourceAccess / -->
     *                 <!--
     *                   By default, the server will provide a DAP2-style response
     *                   to requests for a dataset resource URL. Commenting out the
     *                   "UseDAP2ResourceUrlResponse" element will cause the server
     *                   to return the DAP4 DSR response when a dataset resource URL
     *                   is requested.
     *                 -->
     *                 <UseDAP2ResourceUrlResponse />
     *             </Handler>
     *             <Handler className="opendap.bes.DirectoryDispatchHandler" />
     *             <Handler className="opendap.bes.BESThreddsDispatchHandler"/>
     *             <Handler className="opendap.bes.FileDispatchHandler" />
     */
    private void loadHyraxServiceHandlers(Vector<DispatchHandler> handlers, Element config ) throws Exception {

        if(config==null)
            throw new ServletException("Bad configuration! The configuration element was NULL");

        Element botBlocker = config.getChild("BotBlocker");

        handlers.add(new opendap.bes.VersionDispatchHandler());
        if(botBlocker != null)
            handlers.add(new opendap.coreServlet.BotBlocker());
        handlers.add(new opendap.ncml.NcmlDatasetDispatcher());
        handlers.add(new opendap.threddsHandler.StaticCatalogDispatch());
        handlers.add(new opendap.gateway.DispatchHandler());
        handlers.add(new opendap.bes.BesDapDispatcher());
        handlers.add(new opendap.bes.DirectoryDispatchHandler());
        handlers.add(new opendap.bes.BESThreddsDispatchHandler());
        handlers.add(new opendap.bes.FileDispatchHandler());

        for(DispatchHandler dh:handlers){
            dh.init(this,config);
        }
    }



    /*
     * Navigates the config document to instantiate an ordered list of
     * Dispatch Handlers. Once built the list is searched for a single instance
     * of an OpendapHttpDispatchHandler and a single instance of a
     * ThreddsHandler. Then all of the handlers are initialized by
     * calling their init() methods and passing into them the XML Element
     * that defined them from the config document.
     *
     * @param type             A String containing the name of IsoDispatchHandler list from
     *                         the OLFS to build from.
     * @param dispatchHandlers A Vector in which to store the built
     *                         IsoDispatchHandler instances
     * @param handlerConfigs   A Vector in which to store the configuration
     *                         Element for each IsoDispatchHandler
     * @throws ServletException When things go poorly
     */
    /*
    private void buildHandlers(String type, Vector<DispatchHandler> dispatchHandlers, Vector<Element> handlerConfigs) throws ServletException {

        String msg;

        Element configRoot = configDoc.getRootElement();
        if(configRoot==null)
            throw new ServletException("Bad configuration! No root element in configuration document");

        Element dispatchHandlersElement = configRoot.getChild("DispatchHandlers");
        if(dispatchHandlersElement==null)
            throw new ServletException("Bad configuration! No DispatchHandlers element!");

        Element handlerElements = dispatchHandlersElement.getChild(type);

        log.debug("Building "+ type);

        if(handlerElements!=null){

            for (Object o : handlerElements.getChildren("Handler")) {
                Element handlerElement = (Element) o;
                handlerConfigs.add(handlerElement);
                String className = handlerElement.getAttributeValue("className");
                if(className!=null) {

                    DispatchHandler dh;
                    try {

                        log.debug("Building Handler: " + className);
                        Class classDefinition = Class.forName(className);
                        dh = (DispatchHandler) classDefinition.newInstance();


                    } catch (ClassNotFoundException e) {
                        msg = "Cannot find class: " + className;
                        log.error(msg);
                        throw new ServletException(msg, e);
                    } catch (InstantiationException e) {
                        msg = "Cannot instantiate class: " + className;
                        log.error(msg);
                        throw new ServletException(msg, e);
                    } catch (IllegalAccessException e) {
                        msg = "Cannot access class: " + className;
                        log.error(msg);
                        throw new ServletException(msg, e);
                    } catch (ClassCastException e) {
                        msg = "Cannot cast class: " + className + " to opendap.coreServlet.DispatchHandler";
                        log.error(msg);
                        throw new ServletException(msg, e);
                    } catch (Exception e) {
                        msg = "Caught an " + e.getClass().getName() + " exception.  msg:" + e.getMessage();
                        log.error(msg);
                        throw new ServletException(msg, e);

                    }

                    dispatchHandlers.add(dh);
                }
                else {
                    log.error("buildHandlers() - FAILED to locate the required 'className' attribute in Handler element. SKIPPING.");
                }
            }
        }

        log.debug(type + " Built.");

    }
    */

    /*

    private void intitializeHandlers(Vector<DispatchHandler> dispatchHandlers, Vector<Element> handlerConfigs) throws ServletException {

        log.debug("Initializing Handlers.");
        String msg;

        try {
            DispatchHandler dh;
            Element config;
            for (int i = 0; i < dispatchHandlers.size(); i++) {
                dh = dispatchHandlers.get(i);
                config = handlerConfigs.get(i);
                dh.init(this, config);
            }
        }
        catch (Exception e) {
            msg = "Could not init() a handler! Caught " + e.getClass().getName() + " Msg: " + e.getMessage();
            log.error(msg);
            throw new ServletException(msg, e);
        }


        log.debug("Handlers Initialized.");


    }
*/


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
     * Starts the logging process.
     */
    private void initLogging() {
        LogUtil.initLogging(this);
        log = org.slf4j.LoggerFactory.getLogger(getClass());

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
    @Override
    public void doGet(HttpServletRequest request,
                      HttpServletResponse response) {



        String relativeUrl = ReqInfo.getLocalUrl(request);

        int request_status = HttpServletResponse.SC_OK;

        try {
            Procedure timedProcedure = Timer.start();

            RequestCache.openThreadCache();

            try {

                if(LicenseManager.isExpired(request)){
                    LicenseManager.sendLicenseExpiredPage(request,response);
                    return;
                }

                int reqno = reqNumber.incrementAndGet();
                LogUtil.logServerAccessStart(request, "HyraxAccess", "HTTP-GET", Long.toString(reqno));

                log.debug(Util.getMemoryReport());

                log.debug(ServletUtil.showRequest(request, reqno));
                log.debug(ServletUtil.probeRequest(this, request));


                if(redirectForServiceOnlyRequest(request,response))
                    return;


                log.debug("Requested relative URL: '" + relativeUrl +
                        "' suffix: '" + ReqInfo.getRequestSuffix(request) +
                        "' CE: '" + ReqInfo.getConstraintExpression(request) + "'");



                if (Debug.isSet("probeRequest"))
                    log.debug(ServletUtil.probeRequest(this, request));


                DispatchHandler dh = getDispatchHandler(request, httpGetDispatchHandlers);
                if (dh != null) {
                    log.debug("Request being handled by: " + dh.getClass().getName());
                    dh.handleRequest(request, response);

                } else {
                    request_status = OPeNDAPException.anyExceptionHandler(new NotFound("Failed to locate resource: "+relativeUrl), this, response);
                }
            }
            finally {
                Timer.stop(timedProcedure);
            }
        }
        catch (Throwable t) {
            try {
                request_status = OPeNDAPException.anyExceptionHandler(t, this, response);
            }
            catch(Throwable t2) {
            	try {
            		log.error("\n########################################################\n" +
                                "Request processing failed.\n" +
                                "Normal Exception handling failed.\n" +
                                "This is the last error log attempt for this request.\n" +
                                "########################################################\n", t2);
            	}
            	catch(Throwable t3){
                    // It's boned now.. Leave it be.
            	}
            }
        }
        finally {
            LogUtil.logServerAccessEnd(request_status, "HyraxAccess");
            RequestCache.closeThreadCache();
            log.info("doGet(): Response completed.\n");
        }

        log.info("doGet() - Timing Report: \n{}", Timer.report());
        Timer.reset();
    }
    //**************************************************************************



    private boolean redirectForServiceOnlyRequest(HttpServletRequest req,
                                                  HttpServletResponse res)
            throws IOException {

        if (ReqInfo.isServiceOnlyRequest(req)) {
            String reqURI = req.getRequestURI();
            String newURI = reqURI+"/";
            res.sendRedirect(Scrub.urlContent(newURI));
            log.debug("Sent redirectForServiceOnlyRequest to map the servlet " +
                    "context to a URL that ends in a '/' character!");
            return true;
        }
        return false;
    }
    

    /**
     * @param request  .
     * @param response .
     */
    @Override
    public void doPost(HttpServletRequest request,
                       HttpServletResponse response) {

        String relativeUrl = ReqInfo.getLocalUrl(request);

        int request_status = HttpServletResponse.SC_OK;

        try {
            try {

                RequestCache.openThreadCache();

                int reqno = reqNumber.incrementAndGet();

                LogUtil.logServerAccessStart(request, "HyraxAccess", "HTTP-POST", Long.toString(reqno));

                log.debug(ServletUtil.showRequest(request, reqno));


                log.debug("Requested relative URL: '" + relativeUrl +
                       "' suffix: '" + ReqInfo.getRequestSuffix(request) +
                       "' CE: '" + ReqInfo.getConstraintExpression(request) + "'");

                if (Debug.isSet("probeRequest"))
                    log.debug(ServletUtil.probeRequest(this, request));


                DispatchHandler dh = getDispatchHandler(request, httpPostDispatchHandlers);
                if (dh != null) {
                    log.debug("Request being handled by: " + dh.getClass().getName());
                    dh.handleRequest(request, response);

                } else {
                    request_status = OPeNDAPException.anyExceptionHandler(new NotFound("Failed to locate resource: "+relativeUrl), this, response);
                }



            }
            finally {
                log.info("doPost(): Response completed.\n");
            }

        } catch (Throwable t) {
            try {
                request_status = OPeNDAPException.anyExceptionHandler(t, this, response);
            }
            catch(Throwable t2) {
            	try {
            		log.error("BAD THINGS HAPPENED!", t2);
            	}
            	catch(Throwable t3){
            		// It's boned now.. Leave it be.
            	}
            }
        }
        finally{
            LogUtil.logServerAccessEnd(request_status, "HyraxAccess");
            RequestCache.closeThreadCache();
        }


    }



    /**
     * Returns the first handler in the vector of DispatchHandlers that claims
     * be able to handle the incoming request.
     *
     * @param request The request we are looking to handle
     * @param dhvec   A Vector of DispatchHandlers that will be asked if they can
     *                handle the request.
     * @return The IsoDispatchHandler that can handle the request, null if no
     *         handler claims the request.
     * @throws Exception For bad behaviour.
     */
    private DispatchHandler getDispatchHandler(HttpServletRequest request, Vector<DispatchHandler> dhvec) throws Exception {
        for (DispatchHandler dh : dhvec) {
            log.debug("Checking handler: " + dh.getClass().getName());
            if (dh.requestCanBeHandled(request)) {
                return dh;
            }
        }
        return null;
    }


    /**
     * Gets the last modified date of the requested resource. Because the data handler is really
     * the only entity capable of determining the last modified date the job is passed  through to it.
     *
     * @param req The current request
     * @return Returns the time the HttpServletRequest object was last modified, in milliseconds
     *         since midnight January 1, 1970 GMT
     */
    protected long getLastModified(HttpServletRequest req) {


        RequestCache.openThreadCache();

        long reqno = reqNumber.incrementAndGet();
        LogUtil.logServerAccessStart(req, "HyraxAccess", "LAST-MOD", Long.toString(reqno));

        long lmt = new Date().getTime();

        Procedure timedProcedure = Timer.start();
        try {

            if (ReqInfo.isServiceOnlyRequest(req)) {
                return lmt;
            }


            if (!LicenseManager.isExpired(req) && !ReqInfo.isServiceOnlyRequest(req)) {

                DispatchHandler dh = getDispatchHandler(req, httpGetDispatchHandlers);
                if (dh != null) {
                    log.debug("getLastModified() -  Request being handled by: " + dh.getClass().getName());
                    lmt = dh.getLastModified(req);

                }
            }
        } catch (Exception e) {
            log.error("getLastModifiedTime() - Caught " + e.getClass().getName() + " msg: " + e.getMessage());
            lmt = new Date().getTime();
        } finally {
            LogUtil.logServerAccessEnd(HttpServletResponse.SC_OK, "HyraxAccess");
            Timer.stop(timedProcedure);

        }


        return lmt;

    }





    public void destroy() {

        LogUtil.logServerShutdown("destroy()");

        if(httpGetDispatchHandlers != null){
            for (DispatchHandler dh : httpGetDispatchHandlers) {
                log.debug("Shutting down handler: " + dh.getClass().getName());
                dh.destroy();
            }
        }


        super.destroy();
    }




}
