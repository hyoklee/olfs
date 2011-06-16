/////////////////////////////////////////////////////////////////////////////
// This file is part of the "OPeNDAP 4 Data Server (aka Hyrax)" project.
//
//
// Copyright (c) 2011 OPeNDAP, Inc.
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

package opendap.bes;

import opendap.coreServlet.*;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.ServletOutputStream;
import java.io.PrintWriter;
import java.io.OutputStreamWriter;
import java.io.ByteArrayOutputStream;
import java.util.Date;
import java.util.regex.Pattern;

import org.jdom.Element;

/**
 * User: ndp
 * Date: Apr 16, 2007
 * Time: 4:17:12 PM
 */
public class FileDispatchHandler implements DispatchHandler {

    private org.slf4j.Logger log;
    private boolean allowDirectDataSourceAccess;
    private boolean initialized;

    public FileDispatchHandler() {

        allowDirectDataSourceAccess = false;
        log = org.slf4j.LoggerFactory.getLogger(getClass());
        initialized = false;

    }



    public void init(HttpServlet servlet,Element config) throws Exception {

        if(initialized) return;


        Element dv = config.getChild("AllowDirectDataSourceAccess");
        if(dv!=null){
            allowDirectDataSourceAccess = true;
        }

        log.info("Intialized. Direct Data Source Access: " + (allowDirectDataSourceAccess?"Enabled":"Disabled") );

        initialized = true;

    }

    public boolean requestCanBeHandled(HttpServletRequest request)
            throws Exception {
        return fileDispatch(request, null, false);

    }


    public void handleRequest(HttpServletRequest request,
                              HttpServletResponse response)
            throws Exception {

       if(!fileDispatch(request, response, true))
           log.debug("FileDispatch request failed inexplicably!");

    }


    public long getLastModified(HttpServletRequest req) {

        String name = ReqInfo.getRelativeUrl(req);

        log.debug("getLastModified(): Tomcat requesting getlastModified() for collection: " + name );


        try {
            DataSourceInfo dsi = new BESDataSource(name);
            log.debug("getLastModified(): Returning: " + new Date(dsi.lastModified()));

            return dsi.lastModified();
        }
        catch (Exception e) {
            log.debug("getLastModified(): Returning: -1");
            return -1;
        }


    }



    public void destroy() {
        log.info("Destroy complete.");

    }

    /**
     * Performs dispatching for file requests. If a request is not for a
     * special service or an OPeNDAP service then we attempt to resolve the
     * request to a "file" located within the context of the data handler and
     * return it's contents.
     *
     * @param request      .
     * @param response     .
     * @param sendResponse If this is true a response will be sent. If it is
     *                     the request will only be evaluated to determine if a response can be
     *                     generated.
     * @return true if the request was serviced as a file request, false
     *         otherwise.
     * @throws Exception .
     */
    public boolean fileDispatch(HttpServletRequest request,
                                HttpServletResponse response,
                                boolean sendResponse) throws Exception {


        DataSourceInfo dsi = new BESDataSource(ReqInfo.getRelativeUrl(request));

        boolean isFileResponse = false;

        if (dsi.sourceExists()) {
            if (!dsi.isNode()) {
                isFileResponse = true;
                if (sendResponse) {
                    if(dsi.sourceIsAccesible()){
                        if (!dsi.isDataset() || allowDirectDataSourceAccess) {
                            sendFile(request, response);
                        } else {
                            sendDirectAccessDenied(request, response);
                        }
                    }
                    else {
                        response.sendError(HttpServletResponse.SC_FORBIDDEN);
                    }
                }
            }

        }

        return isFileResponse;

    }


    public void sendFile(HttpServletRequest req,
                         HttpServletResponse response)
            throws Exception {


        String name = ReqInfo.getRelativeUrl(req);


        log.debug("sendFile(): Sending file \"" + name + "\"");

        String downloadFileName = Scrub.fileName(name.substring(name.lastIndexOf("/")+1));

        log.debug("sendFile() downloadFileName: " + downloadFileName );

        String contentDisposition = " attachment; filename=" +downloadFileName;

        response.setHeader("Content-Disposition",contentDisposition);


        String suffix = ReqInfo.getRequestSuffix(req);

        if (suffix != null) {
            String mType = MimeTypes.getMimeType(suffix);

            if (mType != null)
                response.setContentType(mType);

            log.debug("   MIME type: " + mType + "  ");
        }





        response.setStatus(HttpServletResponse.SC_OK);
        ByteArrayOutputStream erros = new ByteArrayOutputStream();


        ServletOutputStream sos = response.getOutputStream();
        if(!BesXmlAPI.writeFile(name, sos, erros)){
            String msg = new String(erros.toByteArray());
            log.error(msg);
            response.sendError(HttpServletResponse.SC_BAD_REQUEST,msg);
        }


    }

    private void sendDirectAccessDenied(HttpServletRequest request, HttpServletResponse response) throws Exception {

        response.setContentType("text/html");
        response.setHeader("Content-Description", "BadURL");
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);

        PrintWriter pw = new PrintWriter(new OutputStreamWriter(response.getOutputStream()));

        String serviceUrl = ReqInfo.getServiceUrl(request);

        pw.println("<h2>ACCESS DENIED</h2>");
        pw.println("<p>The requested URL references a data source directly. </p>" +
                "<p>You must use the OPeNDAP request interface to get data from the data source.</p>");


        pw.println("<p>If you would like to start at the top level of this server, go here:</p>");
        pw.println("<p><a href='" + Scrub.completeURL(serviceUrl) + "'>" + Scrub.completeURL(serviceUrl) + "</a></p>");
        pw.println("<p>If you think that the server is broken (that the URL you");
        pw.println("submitted should have worked), then please contact the");
        pw.println("OPeNDAP user support coordinator at: ");
        pw.println("<a href=\"mailto:support@opendap.org\">support@opendap.org</a></p>");

        pw.flush();


    }


}
