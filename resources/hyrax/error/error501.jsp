<%@ page import="opendap.coreServlet.ReqInfo" %>
<%@ page import="opendap.bes.BesApi" %>
<%@ page import="opendap.coreServlet.OPeNDAPException" %>
<%@ page import="org.owasp.encoder.Encode" %>
<%@ page import="opendap.bes.BadConfigurationException" %>
<%--
  ~ /////////////////////////////////////////////////////////////////////////////
  ~ // This file is part of the "Hyrax Data Server" project.
  ~ //
  ~ //
  ~ // Copyright (c) 2013 OPeNDAP, Inc.
  ~ // Author: Nathan David Potter  <ndp@opendap.org>
  ~ //
  ~ // This library is free software; you can redistribute it and/or
  ~ // modify it under the terms of the GNU Lesser General Public
  ~ // License as published by the Free Software Foundation; either
  ~ // version 2.1 of the License, or (at your option) any later version.
  ~ //
  ~ // This library is distributed in the hope that it will be useful,
  ~ // but WITHOUT ANY WARRANTY; without even the implied warranty of
  ~ // MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
  ~ // Lesser General Public License for more details.
  ~ //
  ~ // You should have received a copy of the GNU Lesser General Public
  ~ // License along with this library; if not, write to the Free Software
  ~ // Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301
  ~ //
  ~ // You can contact OPeNDAP, Inc. at PO Box 112, Saunderstown, RI. 02874-0112.
  ~ /////////////////////////////////////////////////////////////////////////////
  --%>

<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Transitional//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd">
<%@page session="false" %>
<%
    int status = 501;
    String title = "Hyrax - No Such Service ("+status+")";

    String contextPath = request.getContextPath();
    String localUrl = ReqInfo.getLocalUrl(request);
    BesApi besApi = new BesApi();
    String supportEmail;
    try {
        supportEmail = besApi.getSupportEmail(localUrl);
    } catch (BadConfigurationException e) {
        supportEmail=null;
    }

    String message = OPeNDAPException.getAndClearCachedErrorMessage();
    String mailtoHrefAttributeValue = OPeNDAPException.getSupportMailtoLink(request,501,message,supportEmail);
%>
<html xmlns="http://www.w3.org/1999/xhtml">
<head>
<meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
<link rel='stylesheet' href='<%= contextPath %>/docs/css/contents.css' type='text/css' />
<title><%=title%></title>
<style type="text/css">
<!--
.style1 {
	font-size: 24px;
	font-weight: bold;
}
-->
</style>
</head>

<body>
<p>&nbsp;</p>
<h1 align="center"><%=title%></h1>
<hr size="1" noshade="noshade" />
<table width="100%" border="0">
  <tr>
    <td>
        <img src="<%= contextPath %>/docs/images/forbidden.png"
             alt="That's not going to work..."
             title="That's not going to work..."
             width="350" height="313" /><
    </td>
    <td><p align="left" class="style1">Hmmmm...</p>
      <p align="left">You asked for a service that is not provided here.</p>
      <% if (message != null) { %>
      <p align="left">The specific error message associated with your request was:</p>
        <blockquote> <p><strong><%= Encode.forHtml(message) %> </strong></p> </blockquote>
      <% } %>
        <% if(supportEmail!=null){ %>
      <p align="left"> If you think that the server is broken (that the URL you submitted should have worked),
          then please contact the OPeNDAP user support coordinator at:
          <a href="<%=mailtoHrefAttributeValue%>"><%= supportEmail %></a>
      </p>
      <% } %>

    </td>
  </tr>
</table>
<hr size="1" noshade="noshade" />
<h1 align="center"><%=title%></h1>
</body>
</html>
