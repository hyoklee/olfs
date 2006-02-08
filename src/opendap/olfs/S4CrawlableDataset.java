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

import thredds.crawlabledataset.CrawlableDataset;
import thredds.crawlabledataset.CrawlableDatasetFilter;

import java.io.IOException;
import java.util.List;
import java.util.Date;
import java.util.Iterator;
import java.util.Vector;
import java.text.SimpleDateFormat;
import java.text.ParsePosition;

import org.jdom.Document;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jdom.output.XMLOutputter;
import org.jdom.output.Format;
import opendap.ppt.PPTException;
import opendap.util.Debug;

/**
 * Created by IntelliJ IDEA.
 * User: ndp
 * Date: Dec 4, 2005
 * Time: 8:08:25 AM
 * To change this template use File | Settings | File Templates.
 */
public class S4CrawlableDataset implements CrawlableDataset {

    private String _path;
    private String _name;
    private int    _size;
    private Date   _lastModified;

    private boolean _isContainer;

    private String    _parentPath;
    private S4CrawlableDataset _parent;

    private List     _childDatasetElements;

    //private boolean  _isConfigured;
    private boolean  _haveCatalog;


    private Element _config;



    public S4CrawlableDataset(String path, Object o) throws IOException, PPTException, BadConfigurationException, JDOMException, BESException {

        this(path);

        _config = (Element)o;

        //System.out.println("\n\n\n\n\nS4CrawlableDataset config: "+_config);

        //XMLOutputter xo = new XMLOutputter(Format.getPrettyFormat());
        //xo.output(_config,System.out);

        //try{
            configure();
        //}
        //catch(Exception e){
        //    System.out.println("OOPS!");
        //    e.printStackTrace(System.out);
        //}
        //System.out.println("\n\n\n\n\n");

    }
    private S4CrawlableDataset(String path) {

        // Strip off the catalog request
        _path = path.endsWith("/catalog") ? path.substring( 0, path.length() - 8 ) : path;

        // Is path empty? Then make it "/"
        _path = _path.equals("") ? "/" : _path;


        //_path = _path.equals("/") ? "" : _path;   // Does THREDDS want the top to / or empty??

        // Determine name (i.e., last name in the path name sequence).
        _name = _path.endsWith( "/" ) ? _path.substring( 0, _path.length() - 1 ) : _path;

        _name = _name.equals("") ? "/" : _name;
        //_name = _name.equals("/") ? "" : _name;   // Does THREDDS want the top to / or empty??



        _parentPath = null;
        int index = _name.lastIndexOf( "/" );
        if ( index > 0){
            _parentPath = _name.substring(0,index);
            _name = _name.substring( index );
        }
        else
            _parentPath = "/";


        //_isConfigured = false;
        _haveCatalog  = false;

        //if(Debug.isSet("showResponse")){
            System.out.println("S4CrawlableDataset:");
            System.out.println("    _path            = "+_path);
            System.out.println("    _name            = "+_name);
            System.out.println("    lastIndexOf(\"/\") = "+index);
            System.out.println("    _parentPath      = "+_parentPath);
        //}

    }


    private void configure() throws BadConfigurationException,
            IOException, PPTException, JDOMException, BESException {



        if(_config != null){

            //System.out.println("Configuring BES...");
            String besHost = _config.getChildTextTrim("besHost",_config.getNamespace());
            String besPortString = _config.getChildTextTrim("besPort",_config.getNamespace());
            //System.out.println("besHost: "+besHost+"   besPortString: "+besPortString);

            int    besPort = Integer.parseInt(besPortString);

            //System.out.println("besHost: "+besHost+"   besPort: "+besPort+"\n\n");

            BesAPI.configure(besHost,besPort);
        }
        else {
            System.out.println("Looks like we are already configured, checking...");
            if(!BesAPI.isConfigured())
                System.out.println("BES IS NOT CONFIGURED!\n\n\n");
        }

        getInfo();

    }



    public Object getConfigObject(){
        return _config;
    }





    private void getCatalog() throws PPTException, IOException, JDOMException, BadConfigurationException, BESException {

        Document doc = BesAPI.showCatalog(_path);
        Element topDataset = doc.getRootElement();

        if(!_path.equals(topDataset.getChild("name").getTextTrim())){
            throw new IOException ("Returned dataset name does not match requested name.\n"+
                                   "Requested: " + _path + "  "+
                                   "Returned: "+topDataset.getChild("name").getTextTrim());
//            System.out.println("Returned dataset name does not match requested name.\n"+
//                                   "Requested: " + _name + "  "+
//                                   "Returned: "+topDataset.getChild("name").getTextTrim());
        }

        processDatasetElement(topDataset,this);

        _haveCatalog = true;


    }



    private void getInfo() throws PPTException, IOException, JDOMException, BadConfigurationException, BESException {

        Document doc = BesAPI.showInfo(_path);
        Element topDataset = doc.getRootElement();

        if(!_path.equals(topDataset.getChild("name").getTextTrim())){
            throw new IOException ("Returned dataset name does not match requested name.\n"+
                                   "Requested: " + _path + "  "+
                                   "Returned: "+topDataset.getChild("name").getTextTrim());

//            System.out.println("Returned dataset name does not match requested name.\n"+
//                                   "Requested: " + _name + "  "+
//                                   "Returned: "+topDataset.getChild("name").getTextTrim());

        }

        processDatasetElement(topDataset,this);


    }


    private void processDatasetElement(Element dataset, S4CrawlableDataset s4c){

        s4c._name = dataset.getChild("name").getTextTrim();

        s4c._name = s4c._name.equals("/") ? "" : _name;

        s4c._size = Integer.parseInt(dataset.getChild("size").getTextTrim());

        SimpleDateFormat sdf = new SimpleDateFormat();

        s4c._lastModified = sdf.parse(
        dataset.getChild("lastmodified").getChild("date").getTextTrim() +
        dataset.getChild("lastmodified").getChild("time").getTextTrim(),
        new ParsePosition(0));

        String isContainer = dataset.getAttributeValue("thredds_collection");

        if(isContainer.equalsIgnoreCase("true")){

            s4c._isContainer = true;
            s4c._childDatasetElements = dataset.getChildren("dataset");

        }
    }






    public String getPath() {

        return _path;
    }

    public String getName() {
        return _name;
    }

    public CrawlableDataset getParentDataset() throws IOException {

        if(_parent != null){
            return _parent;
        }

        if(_parentPath == null)
            return null;

        try {
            S4CrawlableDataset s4c = new S4CrawlableDataset(_parentPath,_config);
            _parent = s4c;
            return s4c;
        } catch (PPTException e) {
            throw new IOException(e.getMessage());
        } catch (JDOMException e) {
            throw new IOException(e.getMessage());
        } catch (BadConfigurationException e) {
            throw new IOException(e.getMessage());
        } catch (BESException e) {
            throw new IOException(e.getMessage());
        }


    }

    public boolean isCollection() {
        return _isContainer;
    }


    public List listDatasets()  {

        Element e;
        S4CrawlableDataset dataset;


        if(!isCollection())
            return null;

        try {
            if(!_haveCatalog)
                getCatalog();
        }
        catch(Exception ex){
            ex.printStackTrace();
            return null;
        }

        int j = 0;
        Vector childDatasets = new Vector();
        Iterator i = _childDatasetElements.iterator();
        while(i.hasNext()){
            e  = (Element) i.next();


            String newPath = this._path + (_path.equals("/") ? "" : "/") + e.getChild("name").getTextTrim();

            System.out.println("Making new dataset \""+newPath+"\" in listDatasets.");

            dataset = new S4CrawlableDataset(newPath);

            processDatasetElement(e,dataset);

            dataset._parent = this;
            dataset._config = this._config;

            childDatasets.add(dataset);


            j++;

        }

        if(Debug.isSet("showResponse")) System.out.println("List Datasets found "+j+" member(s).");

        return childDatasets;
    }

    public List listDatasets(CrawlableDatasetFilter crawlableDatasetFilter) {

        if(!isCollection())
            return null;

        List l = listDatasets();

        Iterator i = l.iterator();

        while(i.hasNext()){
            CrawlableDataset cd = (CrawlableDataset) i.next();
            if(crawlableDatasetFilter != null && !crawlableDatasetFilter.accept(cd))
                l.remove(cd);
        }


        return l;
    }

    public long length() {
        return _size;
    }

    public Date lastModified() {
        return _lastModified;
    }
}
