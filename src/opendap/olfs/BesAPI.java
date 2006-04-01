/////////////////////////////////////////////////////////////////////////////
// This file is part of the "Server4" project, a Java implementation of the
// OPeNDAP Data Access Protocol.
//
// Copyright (c) 2005 OPeNDAP, Inc.
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


package opendap.olfs;

import opendap.util.Debug;
import opendap.ppt.OPeNDAPClient;
import opendap.ppt.PPTException;

import java.io.OutputStream;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.util.Iterator;

import org.jdom.Document;
import org.jdom.JDOMException;
import org.jdom.Element;
import org.jdom.filter.ElementFilter;
import org.jdom.input.SAXBuilder;


/**
 * Created by IntelliJ IDEA.
 * User: ndp
 * Date: Aug 18, 2005
 * Time: 10:40:00 AM
 * To change this template use File | Settings | File Templates.
 */
public class BesAPI {

    private static int _besPort = -1;
    private static String _besHost = "Not Configured!";
    private static boolean _configured = false;
    private static final Object syncLock = new Object();

    public static boolean configure(String host, int port) {

        synchronized (syncLock) {

            if (isConfigured())
                return false;

            _besHost = host;
            _besPort = port;
            _configured = true;

            System.out.println("BES is configured - Host: " + _besHost + "   Port: " + _besPort);
        }

        return true;

    }

    public static boolean isConfigured() {
        return _configured;
    }

/*
    public static boolean configure(ReqState rs) throws BadConfigurationException {

        String besHost = rs.getInitParameter("BackEndServer");
        if (besHost == null)
            throw new BadConfigurationException("Servlet configuration must included BackEndServer\n");

        String besPort = rs.getInitParameter("BackEndServerPort");
        if (besPort == null)
            throw new BadConfigurationException("Servlet configuration must included BackEndServerPort\n");


        return configure(besHost, Integer.parseInt(besPort));

    }
*/

    public static String getHost() throws BadConfigurationException {
        if (!isConfigured())
            throw new BadConfigurationException("BES must be configured before use!\n");

        return _besHost;
    }

    public static int getPort() throws BadConfigurationException {
        if (!isConfigured())
            throw new BadConfigurationException("BES must be configured before use!\n");
        return _besPort;
    }


    public static void getDDX(String dataset,
                              String constraintExpression,
                              OutputStream os)
            throws BadConfigurationException, PPTException {

        besGetTransaction(getAPINameForDDX(), dataset, constraintExpression, os);
    }

    public static void getDDS(String dataset,
                              String constraintExpression,
                              OutputStream os)
            throws BadConfigurationException, PPTException {

        besGetTransaction(getAPINameForDDS(), dataset, constraintExpression, os);
    }

/*

    public static ServerDDS getDDS(String dataset,
                              String constraintExpression)
            throws BadConfigurationException, PPTException, DDSException, ParseException {

        return getDDS(dataset,constraintExpression,new DefaultFactory());

    }

   public static ServerDDS getDDS(String dataset,
                                  String constraintExpression, BaseTypeFactory btf)
                throws BadConfigurationException, PPTException, DDSException, ParseException {
        OPeNDAPClient oc = BesAPI.startClient();

        BesAPI.configureTransaction(oc,dataset, constraintExpression);

        ByteArrayOutputStream os = new ByteArrayOutputStream();
        BesAPI.getDataProduct(oc,BesAPI.getAPINameForDDS(),os);

        ByteArrayInputStream is = new ByteArrayInputStream(os.toByteArray());
        ServerDDS myDDS = new ServerDDS(btf);
        myDDS.parse(is);

        return myDDS;
    }
*/

    public static void getDAS(String dataset,
                              String constraintExpression,
                              OutputStream os)
            throws BadConfigurationException, PPTException {

        besGetTransaction(getAPINameForDAS(), dataset, constraintExpression, os);
    }

    public static void getDODS(String dataset,
                               String constraintExpression,
                               OutputStream os)
            throws BadConfigurationException, PPTException {

        besGetTransaction(getAPINameForDODS(), dataset, constraintExpression, os);
    }


    public static Document showVersion()
            throws BadConfigurationException, PPTException, IOException, JDOMException, BESException {

        // Get the version response from the BES (an XML doc)
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        besShowTransaction("version", os);

        if (Debug.isSet("BES")) System.out.println(os);

        // Parse the XML doc into a Document object.
        SAXBuilder sb = new SAXBuilder();
        Document doc = sb.build(new ByteArrayInputStream(os.toByteArray()));

        // Check for an exception:
        besExceptionHandler(doc);
        // Tweak it!

        // First find the response Element
        Element ver = doc.getRootElement().getChild("response");

        // Disconnect it from it's parent and then rename it.
        ver.detach();
        ver.setName("OPeNDAP-Version");

        doc.detachRootElement();
        doc.setRootElement(ver);

        return doc;
    }


    public static Document showInfo(String path)
            throws PPTException, BadConfigurationException, IOException, JDOMException, BESException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        String product = "info for " + "\"" + path + "\"";

        if (Debug.isSet("BES")) System.out.println("BESCrawlableDataset sending BES cmd: show " + product);
        BesAPI.besShowTransaction(product, baos);


        if (Debug.isSet("BES")) System.out.println("BES returned:\n" + baos);

        // Parse the XML doc into a Document object.
        SAXBuilder sb = new SAXBuilder();
        Document doc = sb.build(new ByteArrayInputStream(baos.toByteArray()));

        // Check for an exception:
        besExceptionHandler(doc);

        // Prepare the response:

        // First find the response Element

        Element topDataset = doc.getRootElement().getChild("response").getChild("dataset");

        // Disconnect it from it's parent and then rename it.
        topDataset.detach();
        doc.detachRootElement();
        doc.setRootElement(topDataset);

        return doc;

    }


    private static void besExceptionHandler(Document doc) throws BESException {

        ElementFilter exceptionFilter = new ElementFilter("BESException");
        Iterator i = doc.getDescendants(exceptionFilter);
        if (i.hasNext()) {

            String msg = "";
            int j = 0;
            while (i.hasNext()) {
                if (j > 0)
                    msg += "\n";
                Element exception = (Element) i.next();
                msg += "[BESException: " + j++ + "]" +
                        "[Type: " + exception.getChild("Type").getTextTrim() + "]" +
                        "[Message: " + exception.getChild("Message").getTextTrim() + "]" +
                        "[Location: " + exception.getChild("Location").getTextTrim() + "]";


            }
            throw new BESException(msg);
        }

    }


    public static Document showCatalog(String path)
            throws PPTException, BadConfigurationException, IOException, JDOMException, BESException {

        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        String product = "catalog for " + "\"" + path + "\"";

        BesAPI.besShowTransaction(product, baos);

        if (Debug.isSet("BES")) System.out.println("BES returned:\n" + baos);

        // Parse the XML doc into a Document object.
        SAXBuilder sb = new SAXBuilder();
        Document doc = sb.build(new ByteArrayInputStream(baos.toByteArray()));

        // Check for an exception:
        besExceptionHandler(doc);
        // Tweak it!

        // First find the response Element

        Element topDataset = doc.getRootElement().getChild("response").getChild("dataset");

        // Disconnect it from it's parent and then rename it.
        topDataset.detach();
        doc.detachRootElement();
        doc.setRootElement(topDataset);

        return doc;

    }

    public static OPeNDAPClient startClient()
            throws BadConfigurationException, PPTException {


        OPeNDAPClient oc = new OPeNDAPClient();

        if (Debug.isSet("BES"))
            System.out.println("Starting OPeNDAPClient. BES - Host: " + _besHost + "  Port:" + _besPort);

        oc.startClient(getHost(), getPort());

        if (Debug.isSet("BES"))
            oc.setOutput(System.out, true);
        else {
            DevNull devNull = new DevNull();
            oc.setOutput(devNull, true);
        }


        return oc;
    }


    public static void configureTransaction(OPeNDAPClient oc, String dataset, String constraintExpression)
            throws PPTException {
        //String datasetPath = rs.getDataset();
        //String datasetType = "nc"; // No longer required as BES will determine data formats
        //String cName = rs.getDataset();
        //String ce = rs.getConstraintExpression();

        //String cmd = "set container in catalog values "+cName + ", " + datasetPath + ", " + datasetType + ";\n";
        String cmd = "set container in catalog values " + dataset + ", " + dataset + ";\n";
        if (Debug.isSet("BES")) System.out.print("Sending BES command: " + cmd);
        oc.executeCommand(cmd);


        if (Debug.isSet("BES")) System.out.println("ConstraintExpression: " + constraintExpression);


        if (constraintExpression == null || constraintExpression.equalsIgnoreCase("")) {
            cmd = "define d1 as " + dataset + ";\n";
        } else {
            cmd = "define d1 as " + dataset + " with " + dataset + ".constraint=\"" + constraintExpression + "\"  ;\n";

        }

        if (Debug.isSet("BES")) System.out.print("Sending BES command: " + cmd);
        oc.executeCommand(cmd);

    }

    public static String getGetCmd(String product) {
        return "get " + product + " for d1;\n";

    }

    public static String getAPINameForDDS() {
        return "dds";
    }

    public static String getAPINameForDAS() {
        return "das";
    }

    public static String getAPINameForDODS() {
        return "dods";
    }

    public static String getAPINameForDDX() {
        return "ddx";
    }


    public static void getDataProduct(OPeNDAPClient oc,
                                      String product,
                                      OutputStream os) throws PPTException {

        String cmd = getGetCmd(product);
        if (Debug.isSet("BES")) System.err.print("Sending command: " + cmd);

        oc.setOutput(os, false);
        oc.executeCommand(cmd);

    }

    public static void shutdownClient(OPeNDAPClient oc) throws PPTException {
        if (Debug.isSet("BES")) System.out.print("Shutting down client...");

        oc.setOutput(null, false);

        oc.shutdownClient();
        if (Debug.isSet("BES")) System.out.println("Done.");


    }

    private static void besGetTransaction(String product,
                                          String dataset, String constraintExpression,
                                          OutputStream os)
            throws BadConfigurationException, PPTException {

        if (Debug.isSet("BES")) System.out.println("Entered besGetTransaction().");


        OPeNDAPClient oc = startClient();

        configureTransaction(oc, dataset, constraintExpression);

        getDataProduct(oc, product, os);

        shutdownClient(oc);

    }


    public static void besShowTransaction(String product, OutputStream os)
            throws PPTException, BadConfigurationException {


        OPeNDAPClient oc = new OPeNDAPClient();

        //System.out.println("BES - Host: "+_besHost+"  Port:"+_besPort);

        oc.startClient(getHost(), getPort());

        if (Debug.isSet("BES"))
            oc.setOutput(System.out, true);
        else {
            DevNull devNull = new DevNull();
            oc.setOutput(devNull, true);
        }

        String cmd = "show " + product + ";\n";
        if (Debug.isSet("BES")) System.err.print("Sending command: " + cmd);
        oc.setOutput(os, false);
        oc.executeCommand(cmd);

        if (Debug.isSet("BES")) System.out.print("Shutting down client...");
        oc.setOutput(null, false);
        oc.shutdownClient();
        if (Debug.isSet("BES")) System.out.println("Done.");

    }


}
