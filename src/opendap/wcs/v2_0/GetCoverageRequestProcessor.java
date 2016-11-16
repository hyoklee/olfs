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
package opendap.wcs.v2_0;


import opendap.coreServlet.Scrub;
import opendap.wcs.v2_0.http.Attachment;
import opendap.wcs.v2_0.http.MultipartResponse;
import opendap.wcs.v2_0.http.SoapHandler;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.output.Format;
import org.jdom.output.XMLOutputter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Vector;

/**
 * Process GetCoverage requests. Static methods are used to construct a wcs:Coverages
 * response.
 */
public class GetCoverageRequestProcessor {


    private static Logger log = LoggerFactory.getLogger(GetCoverageRequestProcessor.class);


    public static String coveragesContentID = "urn:ogc:wcs:1.1:coverages";


    /**
     * @param req             The GetCoverageRequest object built fromt the client request.
     * @param response        HttpServletResponse object that will receive the response content.
     * @param useSoapEnvelope Instructs the server to make the response a SOAP document.
     * @throws WcsException         When a wcs:Coverage response document cannot be
     *                              constructed for the passed request.
     * @throws InterruptedException When the server may need to stop a (possibly length) request
     * @throws IOException          Wen in the disk or the internets are broken.
     */
    public static void sendCoverageResponse(GetCoverageRequest req, HttpServletResponse response, boolean useSoapEnvelope) throws WcsException, InterruptedException, IOException {

        String id = req.getCoverageID();
        boolean b = CatalogWrapper.hasCoverage(id);

        if (!b)
            throw new WcsException("No such wcs:Coverage: " + Scrub.fileName(id),
                    WcsException.INVALID_PARAMETER_VALUE, "wcs:CoverageId");

        // If the mediaType is specified then we know it must be multipart/related because the spec says that's
        // the only acceptable value (orly?) and the GetCoverageRequest class enforces that rule. And since it
        // only has a single value we know it means we have to send the multipart response with the gml:Coverage
        // in the first part and then the binary stuff as specified in the format parameter in the next part.
        if (req.getMediaType() != null) {

            sendMultipartGmlResponse(req, response, useSoapEnvelope);


        } else {
            sendFormatResponse(req, response, useSoapEnvelope);
        }


    }

    public static void sendFormatResponse(GetCoverageRequest req, HttpServletResponse response, boolean useSoapEnvelope) throws WcsException, InterruptedException, IOException {

        log.debug("Sending binary data response...");

        response.setHeader("Content-Disposition", getContentDisposition(req));

        try {
            opendap.wcs.v2_0.http.Util.forwardUrlContent(getDataAccessUrl(req), response, true);
        } catch (URISyntaxException e) {
            throw new WcsException("Internal server error. Server failed to generate a valid server access URI: " + Scrub.fileName(getDataAccessUrl(req)),
                    WcsException.NO_APPLICABLE_CODE, "DataAccessUrl");
        }


    }


    public static void sendMultipartGmlResponse(GetCoverageRequest req, HttpServletResponse response, boolean useSoapEnvelope) throws WcsException, InterruptedException {


        log.debug("Building multi-part Response...");

        String rangePartId = "cid:" + req.getCoverageID();

        CoverageDescription coverageDescription = CatalogWrapper.getCoverageDescription(req.getCoverageID());

        /**
         * If this an EO coverage then update its bounding box to reflect the subset.
         */
        if(coverageDescription instanceof EOCoverageDescription){
            try {
                // Mke a copy so we don't bunk up the original.
                EOCoverageDescription eocd = new EOCoverageDescription((EOCoverageDescription)coverageDescription);
                // Tweak the foot print
                eocd.adjustEOMetadataCoverageFootprint(req);
                // Make it the thing to use...
                coverageDescription = eocd;
            } catch (IOException e) {
                throw new WcsException("sendMultipartGmlResponse() - OUCH!! Failed to create new (malleable) " +
                        "EOCoverageDescription using the copy constructor.",
                        WcsException.NO_APPLICABLE_CODE);
            }
        }

        Coverage coverage = coverageDescription.getCoverage(req.getRequestUrl()); // new Coverage(coverageDescription, req.getRequestUrl());

        Element coverageElement = coverage.getCoverageElement(rangePartId, getReturnMimeType(req));

        XMLOutputter xmlo = new XMLOutputter(Format.getPrettyFormat());
        log.debug(xmlo.outputString(coverageElement));


        Document doc = new Document(coverageElement);

        if (useSoapEnvelope)
            doc = SoapHandler.wrapDocumentInSoapEnvelope(doc);


        MultipartResponse mpr = new MultipartResponse();

        Attachment gmlPart = new Attachment("application/gml+xml; charset=UTF-8", "gml-part", doc);
        mpr.addAttachment(gmlPart);

        Attachment rangePart = new Attachment(getReturnMimeType(req), rangePartId, getDataAccessUrl(req));
        rangePart.setHeader("Content-Disposition", getContentDisposition(req));

        mpr.addAttachment(rangePart);


        try {
            mpr.send(response);
        } catch (Exception e) {
            StringBuilder msg = new StringBuilder("sendMultipartGmlResponse() - ");
            msg.append("Failed to transmit WCS coverage response.");
            msg.append(" Message: ").append(e.getMessage());
            throw new WcsException(msg.toString(), WcsException.NO_APPLICABLE_CODE);
        }

    }


    public static String getReturnFormat(GetCoverageRequest req) throws WcsException, InterruptedException {
        CoverageDescription coverageDescription = CatalogWrapper.getCoverageDescription(req.getCoverageID());
        String format = req.getFormat();
        if (format == null) {
            format = coverageDescription.getNativeFormat();
        }
        return format;
    }


    public static String getDataAccessUrl(GetCoverageRequest req) throws WcsException, InterruptedException {
        String dataAccessURL;

        String format = getReturnFormat(req);
        if (format.contains("netcdf")) {
            dataAccessURL = getNetcdfDataAccessURL(req);
        } else if (format.equals("image/tiff")) {
            dataAccessURL = getGeoTiffDataAccessURL(req);
        } else if (format.equals("image/jp2")) {
            dataAccessURL = getGmlJpeg2000DataAccessURL(req);
        } else if (format.equals("application/octet-stream")) {
            dataAccessURL = getDap2DataAccessURL(req);
        } else {
            throw new WcsException("Unrecognized response format: '" + Scrub.fileName(format)+"'",
                    WcsException.INVALID_PARAMETER_VALUE, "format");
        }

        return dataAccessURL;
    }


    public static String getContentDisposition(GetCoverageRequest req) throws WcsException, InterruptedException {
        String contentDisposition;

        String format = getReturnFormat(req);
        if (format.contains("netcdf")) {
            contentDisposition = " attachment; filename=\"" + req.getCoverageID() + ".nc\"";
        } else if (format.equals("image/tiff")) {
            contentDisposition = " attachment; filename=\"" + req.getCoverageID() + ".tiff\"";
        } else if (format.equals("image/jp2")) {
            contentDisposition = " attachment; filename=\"" + req.getCoverageID() + ".jp2\"";
        } else if (format.equals("application/octet-stream")) {
            contentDisposition = " attachment; filename=\"" + req.getCoverageID() + ".dods\"";
        } else {
            throw new WcsException("Unrecognized response format: " + Scrub.fileName(format),
                    WcsException.INVALID_PARAMETER_VALUE, "format");
        }

        return contentDisposition;
    }


    public static String getReturnMimeType(GetCoverageRequest req) throws WcsException, InterruptedException {
        String mime_type;

        String format = getReturnFormat(req);
        if (format.contains("netcdf")) {
            mime_type = "application/x-netcdf";
        } else if (format.equals("image/tiff")) {
            mime_type = "image/tiff";
        } else if (format.equals("image/geotiff")) {
            mime_type = "image/geotiff";
        } else if (format.equals("application/octet-stream")) {
            mime_type = "application/octet-stream";
        } else {
            throw new WcsException("Unrecognized response format: " + Scrub.fileName(format),
                    WcsException.INVALID_PARAMETER_VALUE, "format");
        }

        return mime_type;
    }


    public static String getGmlJpeg2000DataAccessURL(GetCoverageRequest req) throws InterruptedException, WcsException {

        String requestURL = CatalogWrapper.getDataAccessUrl(req.getCoverageID());

        String dapCE = getDap2CE(req);
        log.debug("getGmlJpeg2000DataAccessURL() - Dap CE: '{}'",dapCE);

        try {
            requestURL += ".gmljp2" + "?" + java.net.URLEncoder.encode(dapCE, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new WcsException("Failed to construct DAP data Access URL. Primary Message: "+e.getMessage(),WcsException.NO_APPLICABLE_CODE);
        }

        return requestURL;
    }

    public static String getGeoTiffDataAccessURL(GetCoverageRequest req) throws InterruptedException, WcsException {

        String requestURL = CatalogWrapper.getDataAccessUrl(req.getCoverageID());

        String dapCE = getDap2CE(req);
        log.debug("getGeoTiffDataAccessURL() - Dap CE: '{}'",dapCE);

        try {
            requestURL += ".tiff" + "?" + java.net.URLEncoder.encode(dapCE, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new WcsException("Failed to construct DAP data Access URL. Primary Message: "+e.getMessage(),WcsException.NO_APPLICABLE_CODE);
        }

        return requestURL;
    }

    public static String getDap2DataAccessURL(GetCoverageRequest req) throws InterruptedException, WcsException {

        String requestURL = CatalogWrapper.getDataAccessUrl(req.getCoverageID());

        requestURL += ".dods" + "?" + getDap2CE(req);

        return requestURL;
    }


    public static String getNetcdfDataAccessURL(GetCoverageRequest req) throws InterruptedException, WcsException {

        String requestURL = CatalogWrapper.getDataAccessUrl(req.getCoverageID());

        requestURL += ".nc" + "?" + getDap2CE(req);

        return requestURL;
    }


    public static String getMetadataAccessURL(GetCoverageRequest req) throws InterruptedException, WcsException {

        String requestURL = CatalogWrapper.getDataAccessUrl(req.getCoverageID());

        requestURL += ".ddx";
        //requestURL +=  "/" + req.getCoverageID() + ".ddx"+"?"+getDap2CE(req);

        return requestURL;
    }


    private static String getDap2CE(GetCoverageRequest req) throws InterruptedException, WcsException {


        String coverageID = req.getCoverageID();


        CoverageDescription coverageDescription = CatalogWrapper.getCoverageDescription(coverageID);
        Vector<Field> fields = coverageDescription.getFields();
        HashMap<String, DimensionSubset> dimensionSubsets = req.getDimensionSubsets();


        // The user may have provided domain subsets.
        // Let's first just QC the request - We'll make sure that the user is asking for dimension
        // subsets of coordinate dimensions that this field has, and while we do that we will associate
        // every matching DomainCoordinate with the DimensionSubset that it matched.
        LinkedHashMap<String, DomainCoordinate> domainCoordinates = coverageDescription.getDomainCoordinates();
        for(DimensionSubset ds: dimensionSubsets.values()){
            DomainCoordinate dc = domainCoordinates.get(ds.getDimensionId());
            if(dc==null){

                /**
                 * It's likely to happen frequently that the user submits a bad dimension name. So
                 * take the time to give an informative error message.
                 */
                StringBuilder msg = new StringBuilder();
                msg.append("Bad subsetting request.\n");
                msg.append("A subset was requested for dimension '").append(ds.getDimensionId()).append("'");
                msg.append(" and there is no coordinate dimension of that name in the Coverage ");
                msg.append("'").append(coverageDescription.getCoverageId()).append("'\n");

                msg.append("Valid coordinate dimension names for '").append(coverageDescription.getCoverageId()).append("' ");
                msg.append("are: ");
                for(String dcName :domainCoordinates.keySet()){
                    msg.append("\n    ").append(dcName);
                }
                msg.append("\n");

                log.debug(msg.toString());

                throw new WcsException(msg.toString(),
                        WcsException.INVALID_PARAMETER_VALUE,
                        "wcs:dimension");
            }

            ds.setDomainCoordinate(dc);

        }


        /**
         * Determines which fields (variables) will be sent back with the response.
         * If none are specified, all are sent.
         */
        Vector<String> _rangeSubset =  req.getRangeSubset();
        if(_rangeSubset.isEmpty()) {
            // if they didn't ask for a subset of the set of fields, then take them all.
            for (Field field : fields) {
                _rangeSubset.add(field.getName());
            }
        }

        /**
         * Is there a Scale request?
         */
        ScaleRequest sr = req.getScaleRequest();

        StringBuilder dap2CE = new StringBuilder();

        /**
         * Here we begin building the DAP2 CE
         * For every field (variable) to be transmitted we (may) need server side functional expressions,
         * array subset expressions, ect.
         *
         */
        Vector<String> gridSubsetClauses = new Vector<>();
        Vector<String> arraySubsetClauses =  new Vector<>();
        for(String fieldId: _rangeSubset){
            String dapGridArrayName = coverageDescription.getDapGridArrayId(fieldId);

            if(dimensionSubsets.isEmpty()){
                arraySubsetClauses.add(dapGridArrayName);
            }
            else {


                // So we need to process the value based subsets with a call to grid
                // and the array index subsets with an appended array index subset for that.


                StringBuilder ssfGridSubsetClause = new StringBuilder();

                boolean arraySubset = false;

                // Process each dimension subset term the user has submitted
                for (DimensionSubset dimSub : dimensionSubsets.values()) {


                    if(dimSub.isValueSubset()) {
                        // A value subset means that the user supplied values of the domain coordinates that specify
                        // the bounds of the subset that they want
                        if(ssfGridSubsetClause.length()==0){
                            // the first dimension subset needs the grid ssf function
                            // declaration and the name of the Grid array and a comma
                            // separator.
                            ssfGridSubsetClause.append("grid(").append(dapGridArrayName).append(",");
                        }
                        else {
                            // subsequent dimensions just need the comma separator
                            ssfGridSubsetClause.append(",");
                        }
                        // Then we tack on the value constraint expression: "low<=dimName<=high"
                        ssfGridSubsetClause.append(dimSub.getDap2GridValueConstraint());
                    }
                    else if(dimSub.isArraySubset()) {
                        // An Array subset means that user indicated (through the use of integer values in
                        // their subset request) that they are wanting to subset by array index.
                        // Because order of the [] array notation in DAP URL's is important, we collect
                        // all of the user provided array constraints here and then literally sort them out below
                        // for inclusion in the response.

                        DomainCoordinate domCoord =  domainCoordinates.get(dimSub.getDimensionId());
                        domCoord.setArraySubset(dimSub.getDapArrayIndexConstraint());
                        arraySubset = true;
                    }
                    else {
                        throw new WcsException("Unrecognized dimension subset.",WcsException.NO_APPLICABLE_CODE);
                    }
                }

                // So we've processed all the user requested dimension subsets, now we need to build the inditial
                // array subsetting clause if needed.


                StringBuilder arraySubsetClause = new StringBuilder();
                if(arraySubset){
                    arraySubsetClause.append(dapGridArrayName);

                    // We build the subsetting string using the domain coordinates in the order they
                    // appear in the DAP dataset, which is how they MUST occur in the configuration
                    // or this all gets broken.
                    for(DomainCoordinate dc : domainCoordinates.values()){
                        String clause = dc.getArraySubset();
                        clause = clause==null?"[*]":clause;
                        arraySubsetClause.append(clause);
                    }
                }


                if(ssfGridSubsetClause.length()>0){
                    ssfGridSubsetClause.append(")");
                    //if(arraySubsetClause.length()>0){
                    //    ssfGridSubsetClause.append(",");
                    //}
                    gridSubsetClauses.add(ssfGridSubsetClause.toString());
                }


                if(arraySubsetClause.length()>0){
                    arraySubsetClauses.add(arraySubsetClause.toString());
                    //ssfGridSubsetClause.append(arraySubsetClause);
                }

                //dap2CE.append(ssfGridSubsetClause);

            } // dimension subsets
        } // fields
        for(String gridSubsetClause: gridSubsetClauses){
            String comma_as_needed = dap2CE.length()>0 ? "," : "";

            String possiblyScaledGridSubset = sr.getScaleExpression(gridSubsetClause);

            dap2CE.append(comma_as_needed).append(possiblyScaledGridSubset);
        }

        for(String arraySubsetClause: arraySubsetClauses){
            String comma_as_needed = dap2CE.length()>0 ? "," : "";
            dap2CE.append(comma_as_needed).append(arraySubsetClause);
        }

        try {
            log.debug("getDap2CE() - DAP2 CE: {}",dap2CE);
            return URLEncoder.encode(dap2CE.toString(), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            log.error("getDap2CE() - Unable to URLEncoder.encode() DAP CE: '{}'",dap2CE);
            throw new WcsException("Failed URL encode DAP2 CE: "+dap2CE+"'",WcsException.NO_APPLICABLE_CODE);
        }

    }


}
