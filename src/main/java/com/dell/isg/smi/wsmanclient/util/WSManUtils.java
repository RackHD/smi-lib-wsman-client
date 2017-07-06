/**
 * Copyright � 2017 DELL Inc. or its subsidiaries.  All Rights Reserved.
 */
package com.dell.isg.smi.wsmanclient.util;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Formatter;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.bind.JAXBElement;
import javax.xml.bind.annotation.XmlSchema;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.soap.SOAPBody;
import javax.xml.stream.XMLInputFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import com.dell.isg.smi.commons.utilities.datetime.DateTimeUtils;
import com.dell.isg.smi.commons.utilities.stream.StreamUtils;
import com.dell.isg.smi.wsmanclient.WSCommandRNDConstant;
import com.dell.isg.smi.wsmanclient.WSManException;
import com.dell.isg.smi.wsmanclient.WSManRuntimeException;

public final class WSManUtils {
    
    /**
     * Instantiates a new WS man utils.
     */
    private WSManUtils() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(WSManUtils.class);

    //private static final SimpleDateFormat WSMAN_DATE_FORMAT = new SimpleDateFormat("yyyyMMddHHmmss.SSSSSSZ");
    private static final Pattern WSMAN_DATE_PATTERN = Pattern.compile("^[0-9]{14}\\.[0-9]{6}\\+[0-9]{3}");
    private static final String WSMAN_DATE_FORMAT = "yyyyMMddHHmmss";

    static final DocumentBuilderFactory DOCUMENT_BUILDER_FACTORY = buildDocumentFactory();
    static final XMLInputFactory XML_INPUT_FACTORY = XMLInputFactory.newInstance();

    private static final ThreadLocal<DocumentBuilder> DOCUMENT_BUILDER_THREAD_LOCAL = new ThreadLocal<DocumentBuilder>();


    /**
     * To document helper.
     *
     * @param xmlRecords the xml records
     * @param isRetry the is retry
     * @return the document
     * @throws WSManException the WS man exception
     */
    private static Document toDocumentHelper(String xmlRecords, boolean isRetry) throws WSManException {
        if (null == xmlRecords || StringUtils.isEmpty(xmlRecords)) {
            throw new WSManException("xmlRecords input source is null or blank");
        }
        Reader reader = null;
        Document doc = null;
        try {
            DocumentBuilder db = DOCUMENT_BUILDER_FACTORY.newDocumentBuilder();
            InputSource is = new InputSource();
            reader = new StringReader(xmlRecords);
            if (isRetry) {
                reader = new XMLFixingReader(reader);
            }
            is.setCharacterStream(reader);
            doc = db.parse(is);
        } catch (ParserConfigurationException e) // should not occur; we have a fixed configuration
        {
            LOGGER.error("ParserConfigurationException ", e);
        } catch (SAXException e) {
            if (isRetry) {
                LOGGER.error("SAXException: Failed to parse XML", e);
            } else {
                // The return value from EnumerateIDRACCardStrCmd on 10.255.4.76 currently contains a username
                // with characters that are not valid XML characters. SAX therefore fails to parse it.
                // Here we try again with an XMLFixingReader which will replace all invalid characters
                // with '?' to see if we can fix that problem.
                LOGGER.warn("Failed to parse XML; retrying with stream stripped of invalid characters", e);
                doc = toDocumentHelper(xmlRecords, true);
            }
        } catch (IOException e) // should not occur; all our IO is on Strings
        {
            LOGGER.error("IOException ", e);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    LOGGER.warn("Failed to close reader", e);
                }
            }
        }

        if (doc == null) {
            throw new WSManException("Failed to parse XML");
        }
        return doc;
    }


    /**
     * To document.
     *
     * @param xmlRecords the xml records
     * @return the document
     * @throws WSManException the WS man exception
     */
    public static Document toDocument(String xmlRecords) throws WSManException {
        return toDocumentHelper(xmlRecords, false);
    }


    /**
     * Checks if is matching element.
     *
     * @param node the node
     * @param tagName the tag name
     * @return true, if is matching element
     */
    public static boolean isMatchingElement(Node node, String tagName) {
        // Why are we doing equalsIgnoreCase? Aren't xml tags case-sensitive? This
        // check was copied from existing spectre WSMan code...
        return node.getNodeType() == Element.ELEMENT_NODE && node.getLocalName().equalsIgnoreCase(tagName);
    }


    /**
     * Find object in document.
     *
     * @param doc the doc
     * @param xPathLocation the x path location
     * @param qname the qname
     * @param commandEnum the command enum
     * @return the object
     * @throws XPathExpressionException the x path expression exception
     */
    public static Object findObjectInDocument(SOAPBody doc, String xPathLocation, QName qname, Enum<?> commandEnum) throws XPathExpressionException {
        XPathFactory factory = XPathFactory.newInstance();
        XPath xpath = factory.newXPath();
        xpath.setNamespaceContext(new PersonalNamespaceContext(buildResourceURI(commandEnum)));
        XPathExpression expr = xpath.compile(xPathLocation);
        Object result = expr.evaluate(doc, qname);
        return result;
    }


    /**
     * Builds the document factory.
     *
     * @return the document builder factory
     */
    private static DocumentBuilderFactory buildDocumentFactory() {
        DocumentBuilderFactory ret = DocumentBuilderFactory.newInstance();
        ret.setNamespaceAware(true);
        return ret;
    }


    /**
     * Builds the resource URI.
     *
     * @param commandEnum the command enum
     * @return the string
     */
    public static String buildResourceURI(Enum<?> commandEnum) {
        StringBuilder b = new StringBuilder();
        b.append(WSCommandRNDConstant.WSMAN_BASE_URI);
        b.append(WSCommandRNDConstant.WS_OS_SVC_NAMESPACE);
        b.append(commandEnum);
        return b.toString();
    }

    // buildResourceURI returns a schemas.dmtf.org namespace which the iDrac will accept as
    // input but the response namespaces are in schemas.dell.com. For now we buildDellResourceURI
    // can be used to get that version but in the future the resource URI should probably come
    /**
     * Builds the dell resource URI.
     *
     * @param commandEnum the command enum
     * @return the string
     * 
     *  returns a schemas.dmtf.org namespace which the iDrac will accept as
     *  input but the response namespaces are in schemas.dell.com. For now we buildDellResourceURI
     *  can be used to get that version but in the future the resource URI should probably come
     */
    // directly from the CommandEnum so that we can support non-Dell resource URIs.
    static String buildDellResourceURI(Enum<?> commandEnum) {
        StringBuilder b = new StringBuilder();
        b.append("http://schemas.dell.com/wbem/wscim/1/cim-schema/2/");
        b.append(commandEnum);
        return b.toString();
    }


    /**
     * Gets the WS man exception.
     *
     * @param format the format
     * @param args the args
     * @return the WS man exception
     */
    private static WSManException getWSManException(String format, Object... args) {
        StringBuilder sb = new StringBuilder();
        Formatter formatter = null;
        String formattedMessage = null;
        try{
            formatter =  new Formatter(sb, Locale.US);
            formattedMessage = formatter.format(format, args).toString();
        }
        finally{
            StreamUtils.closeStreamQuietly(formatter);
        }
        return new WSManException(formattedMessage);
    }


    /**
     * Try cast.
     *
     * @param <T> the generic type
     * @param o the o
     * @param type the type
     * @return the t
     * @throws WSManException the WS man exception
     */
    private static <T> T tryCast(Object o, Class<T> type) throws WSManException {
        if (o == null)
            return null;
        else {
            Class<?> targetType = o.getClass();
            if (type.isAssignableFrom(targetType))
                return type.cast(o);
            else if (o instanceof JAXBElement) {
                JAXBElement<?> element = (JAXBElement<?>) o;
                Object value = element.getValue();
                targetType = value.getClass();
                if (type.isAssignableFrom(targetType))
                    return type.cast(value);
            }
        }
        return null;
    }


    /**
     * Cast or throw.
     *
     * @param <T> the generic type
     * @param o the o
     * @param type the type
     * @param exceptionFormat the exception format
     * @return the t
     * @throws WSManException the WS man exception
     */
    static <T> T castOrThrow(Object o, Class<T> type, String exceptionFormat) throws WSManException {
        if (o == null)
            return null;
        else {
            Class<?> targetType = o.getClass();
            if (type.isAssignableFrom(targetType))
                return type.cast(o);
            else if (o instanceof JAXBElement) {
                JAXBElement<?> element = (JAXBElement<?>) o;
                Object value = element.getValue();
                targetType = value.getClass();
                if (type.isAssignableFrom(targetType))
                    return type.cast(value);
            }
            throw getWSManException(exceptionFormat, targetType.getSimpleName());
        }
    }


    /**
     * Find and cast or throw.
     *
     * @param <T> the generic type
     * @param objects the objects
     * @param type the type
     * @return the t
     * @throws WSManException the WS man exception
     */
    private static <T> T findAndCastOrThrow(List<Object> objects, Class<T> type) throws WSManException {
        for (Object object : objects) {
            T ret = tryCast(object, type);
            if (ret != null)
                return ret;
        }
        throw new WSManException("Could not find " + type.getSimpleName() + " object in response");
    }


    /**
     * Returns the namespace of the JAXB-annotated class enumerationClass by examining the class's package for an @XMLSchema annotation.
     *
     * @param <T> class type
     * @param enumerationClass JAXB-annotated class
     * @return The class's namespace, or null if none.
     */
    public static <T> String findJAXBNamespace(Class<T> enumerationClass) {
        String nsURI = null;
        for (Annotation annotation : enumerationClass.getPackage().getAnnotations()) {
            if (annotation.annotationType() == XmlSchema.class) {
                nsURI = ((XmlSchema) annotation).namespace();
                break;
            }
        }
        return nsURI;
    }


    /**
     * New document.
     *
     * @return the document
     */
    public static Document newDocument() {
        DocumentBuilder builder = DOCUMENT_BUILDER_THREAD_LOCAL.get();
        if (builder == null) {
            try {
                DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
                builder = documentBuilderFactory.newDocumentBuilder();
                DOCUMENT_BUILDER_THREAD_LOCAL.set(builder);
            } catch (ParserConfigurationException e) {
                // We are not using any custom parser configuration so this should be impossible.
                throw new WSManRuntimeException(e);
            }
        }
        return builder.newDocument();
    }
    
    
    public static Object toObjectMap(Document response)  {
        Element element = response.getDocumentElement();
        NodeList nodeList = element.getElementsByTagNameNS(WSCommandRNDConstant.WS_MAN_NAMESPACE, WSCommandRNDConstant.WSMAN_ITEMS_TAG);
        return processNodeList(nodeList);
    }
    

    /**
     * @param nodeList
     * @return Object - Either a Map or a List
     */
    private static Object processNodeList(NodeList nodeList) {
        Map<String, Object> nodeMap = new HashMap<String, Object>();
        List<Object> listNodes = null;
        Map<String, Object> nm;
        
        for (int i = 0; i < nodeList.getLength(); i++) {
            Node node = nodeList.item(i);
            if (node.getNodeType() == Element.ELEMENT_NODE) {
                if (node.hasChildNodes() && node.getChildNodes().item(0).getNodeType() == Element.ELEMENT_NODE) {
                    Object result = processNodeList(node.getChildNodes());
                    if(result instanceof Map){
                        nm = (Map<String, Object>) result;
                        if(!nm.isEmpty()) {
                            if(listNodes != null) {
                                listNodes.add(nm);
                            } else if(nodeMap.get(node.getLocalName()) != null) {                                
                                listNodes = new ArrayList<Object>();
                                listNodes.add(nodeMap.get(node.getLocalName()));
                                listNodes.add(nm);
                            } else {
                                nodeMap.put(node.getLocalName(), nm);
                            }
                        }
                    } else { // if we get here we must have a List
                        nodeMap.put(node.getLocalName(), result);
                    }                        
                } else {
                    String key = node.getLocalName();
                    String content = node.getTextContent();
                    if(isDate(content, WSMAN_DATE_PATTERN)) {
                        content = getDateString(content, WSMAN_DATE_FORMAT);
                    }
                    if (nodeMap.containsKey(key) == false) {
                        nodeMap.put(key, content);
                    } else {
                        Object o = nodeMap.get(key);
                        if(!(o instanceof List)) {
                            List<Object> valueList = new LinkedList<>();
                            valueList.add(o);
                            valueList.add(content);
                            nodeMap.put(key, valueList);
                        } else {
                            List<Object> list = (List<Object>) nodeMap.get(key);
                            list.add(content);
                        }
                    }
                }
            }
        }
        if(listNodes != null){
            return listNodes;
        }
        if(nodeMap.size() == 1) {
            return nodeMap.get(nodeMap.keySet().iterator().next());
        }
        return nodeMap;
    }


    /**
     * Matches input string with pattern string.
     *
     * @param inputStr the input str
     * @param patternStr the pattern str
     * @return bool value
     */
    public static boolean isDate(String str, Pattern pattern) {
        if (str == null) {
            return false;
        }
        Matcher matcher = pattern.matcher(str);
        if (matcher.matches()) {
            return true;
        }
        return false;
    }


    /**
     * @param dateString
     * @param dateFormat
     * @return
     */
    private static String getDateString(String dateString, String dateFormat) {
        try{
            return DateTimeUtils.getUtcDateFromString(dateFormat, dateString).toString();
        }
        catch(Exception e)
        {
            return dateString;
        }
    }

}
