//
// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
//

package org.apache.cloudstack.utils.auth;

import org.apache.log4j.Logger;
import org.joda.time.DateTime;
import org.opensaml.Configuration;
import org.opensaml.DefaultBootstrap;
import org.opensaml.common.SAMLVersion;
import org.opensaml.common.xml.SAMLConstants;
import org.opensaml.saml2.core.AuthnContextClassRef;
import org.opensaml.saml2.core.AuthnContextComparisonTypeEnumeration;
import org.opensaml.saml2.core.AuthnRequest;
import org.opensaml.saml2.core.Issuer;
import org.opensaml.saml2.core.NameIDPolicy;
import org.opensaml.saml2.core.NameIDType;
import org.opensaml.saml2.core.RequestedAuthnContext;
import org.opensaml.saml2.core.Response;
import org.opensaml.saml2.core.impl.AuthnContextClassRefBuilder;
import org.opensaml.saml2.core.impl.AuthnRequestBuilder;
import org.opensaml.saml2.core.impl.IssuerBuilder;
import org.opensaml.saml2.core.impl.NameIDPolicyBuilder;
import org.opensaml.saml2.core.impl.RequestedAuthnContextBuilder;
import org.opensaml.xml.ConfigurationException;
import org.opensaml.xml.io.Marshaller;
import org.opensaml.xml.io.MarshallingException;
import org.opensaml.xml.io.Unmarshaller;
import org.opensaml.xml.io.UnmarshallerFactory;
import org.opensaml.xml.io.UnmarshallingException;
import org.opensaml.xml.util.Base64;
import org.opensaml.xml.util.XMLHelper;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.FactoryConfigurationError;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URLEncoder;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

public class SAMLUtils {
    public static final Logger s_logger = Logger.getLogger(SAMLUtils.class);

    public static final String SAML_NS = "saml-";

    public static String createSAMLId(String uid) {
        return SAML_NS + uid;
    }

    public static Boolean checkSAMLUserId(String uuid) {
        return uuid.startsWith(SAML_NS);
    }

    public static AuthnRequest buildAuthnRequestObject(String authnId, String spId, String idpUrl, String consumerUrl) {
        // Issuer object
        IssuerBuilder issuerBuilder = new IssuerBuilder();
        Issuer issuer = issuerBuilder.buildObject();
        issuer.setValue(spId);

        // NameIDPolicy
        NameIDPolicyBuilder nameIdPolicyBuilder = new NameIDPolicyBuilder();
        NameIDPolicy nameIdPolicy = nameIdPolicyBuilder.buildObject();
        nameIdPolicy.setFormat(NameIDType.PERSISTENT);
        nameIdPolicy.setSPNameQualifier("Apache CloudStack");
        nameIdPolicy.setAllowCreate(true);

        // AuthnContextClass
        AuthnContextClassRefBuilder authnContextClassRefBuilder = new AuthnContextClassRefBuilder();
        AuthnContextClassRef authnContextClassRef = authnContextClassRefBuilder.buildObject(
                SAMLConstants.SAML20_NS,
                "AuthnContextClassRef", "saml");
        authnContextClassRef.setAuthnContextClassRef("urn:oasis:names:tc:SAML:2.0:ac:classes:PasswordProtectedTransport");

        // AuthnContex
        RequestedAuthnContextBuilder requestedAuthnContextBuilder = new RequestedAuthnContextBuilder();
        RequestedAuthnContext requestedAuthnContext = requestedAuthnContextBuilder.buildObject();
        requestedAuthnContext
                .setComparison(AuthnContextComparisonTypeEnumeration.MINIMUM);
        requestedAuthnContext.getAuthnContextClassRefs().add(
                authnContextClassRef);

        // Creation of AuthRequestObject
        AuthnRequestBuilder authRequestBuilder = new AuthnRequestBuilder();
        AuthnRequest authnRequest = authRequestBuilder.buildObject();
        authnRequest.setID(authnId);
        authnRequest.setDestination(idpUrl);
        authnRequest.setVersion(SAMLVersion.VERSION_20);
        authnRequest.setForceAuthn(true);
        authnRequest.setIsPassive(false);
        authnRequest.setIssuer(issuer);
        authnRequest.setIssueInstant(new DateTime());
        authnRequest.setProviderName("Apache CloudStack");
        authnRequest.setProtocolBinding(SAMLConstants.SAML2_REDIRECT_BINDING_URI);
        authnRequest.setAssertionConsumerServiceURL(consumerUrl);
        authnRequest.setNameIDPolicy(nameIdPolicy);
        authnRequest.setRequestedAuthnContext(requestedAuthnContext);

        return authnRequest;
    }

    public static String encodeSAMLRequest(AuthnRequest authnRequest)
            throws MarshallingException, IOException {
        Marshaller marshaller = Configuration.getMarshallerFactory()
                .getMarshaller(authnRequest);
        Element authDOM = marshaller.marshall(authnRequest);
        StringWriter requestWriter = new StringWriter();
        XMLHelper.writeNode(authDOM, requestWriter);
        String requestMessage = requestWriter.toString();
        Deflater deflater = new Deflater(Deflater.DEFLATED, true);
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        DeflaterOutputStream deflaterOutputStream = new DeflaterOutputStream(byteArrayOutputStream, deflater);
        deflaterOutputStream.write(requestMessage.getBytes());
        deflaterOutputStream.close();
        String encodedRequestMessage = Base64.encodeBytes(byteArrayOutputStream.toByteArray(), Base64.DONT_BREAK_LINES);
        encodedRequestMessage = URLEncoder.encode(encodedRequestMessage, "UTF-8").trim();
        return encodedRequestMessage;
    }

    public static Response decodeSAMLResponse(String responseMessage)
            throws ConfigurationException, ParserConfigurationException,
            SAXException, IOException, UnmarshallingException {
        try {
            DefaultBootstrap.bootstrap();
        } catch (ConfigurationException | FactoryConfigurationError e) {
            s_logger.error("SAML response message decoding error: " + e.getMessage());
        }
        DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
        documentBuilderFactory.setNamespaceAware(true);
        DocumentBuilder docBuilder = documentBuilderFactory.newDocumentBuilder();
        byte[] base64DecodedResponse = Base64.decode(responseMessage);
        Document document = docBuilder.parse(new ByteArrayInputStream(base64DecodedResponse));
        Element element = document.getDocumentElement();
        UnmarshallerFactory unmarshallerFactory = Configuration.getUnmarshallerFactory();
        Unmarshaller unmarshaller = unmarshallerFactory.getUnmarshaller(element);
        return (Response) unmarshaller.unmarshall(element);
    }

}
