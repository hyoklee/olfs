/////////////////////////////////////////////////////////////////////////////
// This file is part of the "OPeNDAP 4 Data Server (aka Hyrax)" project.
//
//
// Copyright (c) 2008 OPeNDAP, Inc.
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
package opendap.wcs.v1_1_2;

import org.jdom.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * User: ndp
 * Date: Aug 18, 2008
 * Time: 1:32:03 PM
 */
public class RangeSubset {


    private static Logger log = LoggerFactory.getLogger(RangeSubset.class);
    private FieldSubset[] fieldSubsets;

    /**
     *
     * @param kvp Key Value Pairs from WCS URL
     * @throws WcsException  When things don't go well.
     */
    public RangeSubset(HashMap<String,String> kvp) throws WcsException{

        //@todo IMPLEMENT KVP INVOCATION OF RANGE SUBSETTING

        String s = kvp.get("RangeSubset");

        if(s != null)
            throw new WcsException("RangeSubsetting is not yet implmented for KVP requests.",
                    WcsException.OPERATION_NOT_SUPPORTED,
                    "RangeSubset");

        log.info("Todo: Range sub-setting is not yet supported for kvp requests.");

        fieldSubsets = new FieldSubset[0];
    }



    public RangeSubset(Element rangeSubset) throws WcsException{

        Element e;
        Iterator i;
        int index;

        WCS.checkNamespace(rangeSubset,"RangeSubset",WCS.WCS_NS);

        List rsl = rangeSubset.getChildren("FieldSubset",WCS.WCS_NS);
        log.error("ERROR - range sub-setting is not supported for kvp requests.");

        if(rsl.size()==0){
            throw new WcsException("The wcs:RangeSubset element is required to have one or more wcs:FieldSubset child elements.",
                    WcsException.MISSING_PARAMETER_VALUE,
                    "wcs:FieldSubset");
        }

        fieldSubsets = new FieldSubset[rsl.size()];
        i = rsl.iterator();
        index = 0;
        while(i.hasNext()){
            e = (Element) i.next();
            fieldSubsets[index++] = new FieldSubset(e);

        }

    }


    public FieldSubset[] getFieldSubsets(){
        return fieldSubsets;

    }


    public Element getElement(){
        Element range = new Element("RangeSubset",WCS.WCS_NS);

        for(FieldSubset fs: fieldSubsets){
            range.addContent(fs.getElement());
        }
        return range;
    }







    class FieldSubset{
        String _id;
        URI _codeType;
        String _interpolationType;
        AxisSubset[] _axisSubsets;

        void init(){
            _id = null;
            _codeType = null;
            _interpolationType = null;
            _axisSubsets = null;
        }

        FieldSubset(Element fs) throws WcsException{
            init() ;

            Element e;
            String  s;

            WCS.checkNamespace(fs,"FieldSubset",WCS.WCS_NS);



            e = fs.getChild("Identifier",WCS.OWS_NS);
            if(e==null){
                throw new WcsException("The wcs:FieldSubset is required to have a ows:Identifier child element.",
                        WcsException.MISSING_PARAMETER_VALUE,
                        "wcs:FieldSubset/ows:Identifier");
            }
            _id = e.getTextNormalize();
            s = e.getAttributeValue("codeSpace");
            if(s!=null){
                try {
                    _codeType = new URI(s);
                }
                catch(URISyntaxException use){
                    throw new WcsException(use.getMessage(),
                            WcsException.INVALID_PARAMETER_VALUE,
                            "ows:Identifier@codeType");
                }
            }


            e = fs.getChild("InterpolationType",WCS.WCS_NS);
            if(e!=null){
                _interpolationType = e.getText();
            }

            List asl = fs.getChildren("AxisSubset",WCS.WCS_NS);
            _axisSubsets = new AxisSubset[asl.size()];
            Iterator i = asl.iterator();
            int index = 0;
            while(i.hasNext()){
                e = (Element) i.next();
                _axisSubsets[index++] = new AxisSubset(e);
            }

        }


        String getID(){
            return _id;
        }

        URI getIDCodeType(){
            return _codeType;
        }

        String getInterpolationType(){
            return _interpolationType;
        }

        AxisSubset[] getAxisSubsets(){
            return _axisSubsets;
        }

        Element getElement(){
            Element fieldSubset = new Element("FieldSubset",WCS.WCS_NS);

            Element e = new Element("Identifier",WCS.OWS_NS);
            if(_codeType!=null)
                e.setAttribute("codeType",_codeType.toASCIIString());
            fieldSubset.addContent(e);

            if(_interpolationType!=null) {
                e = new Element("InterpolationType",WCS.WCS_NS);
                e.setText(_interpolationType);
                fieldSubset.addContent(e);
            }



            for(AxisSubset as: _axisSubsets){
                fieldSubset.addContent(as.getElement());
            }

         return fieldSubset;
        }



    }

    class AxisSubset {
        String _id;
        String[] _keyIDs;


        void init(){
            _id = null;
            _keyIDs = null;
        }

        AxisSubset(Element as) throws WcsException {

            init();

            Element e;

            WCS.checkNamespace(as,"AxisSubset",WCS.WCS_NS);

            e = as.getChild("Identifier",WCS.WCS_NS);
            if(e==null){
                throw new WcsException("The wcs:AxisSubset is required to have a wcs:Identifier child element.",
                        WcsException.MISSING_PARAMETER_VALUE,
                        "wcs:AxisSubset/wcs:Identifier");
            }
            _id = e.getTextNormalize();


            List keyList = as.getChildren("Key",WCS.WCS_NS);

            _keyIDs = new String[keyList.size()];

            Iterator i = keyList.iterator();
            int index = 0;
            while(i.hasNext()){
                e = (Element)i.next();
                _keyIDs[index++] = e.getText();
            }
        }

        String getID(){
            return _id;
        }

        String[] getKeys(){
            return _keyIDs;
        }

        Element getElement(){
            Element axisSubset = new Element("AxisSubset",WCS.WCS_NS);
            Element e = new Element("Identifier",WCS.WCS_NS);
            e.setText(_id);
            axisSubset.addContent(e);

            for(String keyID: _keyIDs){
                e = new Element("Key",WCS.WCS_NS);
                e.setText(keyID);
                axisSubset.addContent(e);
            }

            return axisSubset;

            
        }


    }

}
