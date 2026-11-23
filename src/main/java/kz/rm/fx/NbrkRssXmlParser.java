package kz.rm.fx;

import org.springframework.stereotype.Component;
import org.xml.sax.InputSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * FX-R1A.2: strict, offline parser for NBRK's RSS rate item format.
 * No networking, persistence or commercial price updates.
 * Parsed values are UNVERIFIED until a separately qualified trusted transport
 * associates source URL and response evidence with the body.
 */
@Component
public final class NbrkRssXmlParser {
    private static final int MAX_BYTES=256*1024;
    private static final int MAX_ITEMS=256;
    private static final DateTimeFormatter FOUR_YEAR=
            DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter TWO_YEAR=
            DateTimeFormatter.ofPattern("dd.MM.uu").withResolverStyle(ResolverStyle.STRICT);

    public record ParsedRate(FxRateProvider.Rate rate, String source) {}

    public List<ParsedRate> parse(byte[] xml, LocalDate requestedDate) {
        if(xml==null || xml.length==0 || xml.length>MAX_BYTES || requestedDate==null)
            throw new IllegalArgumentException("Invalid NBRK XML input/date");
        // Explicit lexical denial complements the XML parser's DOCTYPE and
        // external entity protections (including parameter entities).
        String body=new String(xml,StandardCharsets.UTF_8);
        if(body.contains("<!DOCTYPE") || body.contains("<!ENTITY")
                || body.contains("<!doctype") || body.contains("<!entity"))
            throw new IllegalArgumentException("NBRK_XML_DTD_FORBIDDEN");
        final Document document;
        try {
            DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
            var builder=factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler(){
                @Override public void fatalError(org.xml.sax.SAXParseException e)
                        throws org.xml.sax.SAXException { throw e; }
            });
            document=builder.parse(new InputSource(new ByteArrayInputStream(xml)));
        } catch(Exception e) {
            throw new IllegalArgumentException("NBRK_XML_INVALID",e);
        }

        Element root=document.getDocumentElement();
        if(root==null || !"rss".equals(root.getTagName()))
            throw new IllegalArgumentException("NBRK_XML_EXPECTED_RSS");
        Element channel=singleChild(root,"channel");
        List<Element> items=children(channel,"item");
        if(items.isEmpty() || items.size()>MAX_ITEMS)
            throw new IllegalArgumentException("NBRK_XML_ITEM_COUNT_INVALID");

        List<ParsedRate> output=new ArrayList<>();
        Set<String> seen=new HashSet<>();
        for(Element item:items) {
            String currency=text(item,"title").trim();
            if(!currency.matches("[A-Z]{3}") || "KZT".equals(currency))
                throw new IllegalArgumentException("NBRK_XML_CURRENCY_INVALID");
            try { java.util.Currency.getInstance(currency); }
            catch(IllegalArgumentException e) { throw new IllegalArgumentException("NBRK_XML_CURRENCY_INVALID",e); }

            LocalDate effective=date(text(item,"pubDate").trim());
            // The historical date endpoint must never return a later date than requested.
            // Earlier dates are allowed only when the official source explicitly declares them.
            if(effective.isAfter(requestedDate))
                throw new IllegalArgumentException("NBRK_XML_FUTURE_EFFECTIVE_DATE");
            final BigDecimal rate,nominal;
            try {
                rate=new BigDecimal(text(item,"description").trim().replace(",","."));
                nominal=new BigDecimal(text(item,"quant").trim());
            } catch(NumberFormatException e) {
                throw new IllegalArgumentException("NBRK_XML_DECIMAL_INVALID",e);
            }
            if(rate.signum()<=0 || nominal.signum()<=0
                    || rate.precision()>24 || rate.scale()>10
                    || nominal.precision()>19 || nominal.scale()>6)
                throw new IllegalArgumentException("NBRK_XML_RATE_RANGE_INVALID");
            if(!seen.add(currency))
                throw new IllegalArgumentException("NBRK_XML_DUPLICATE_CURRENCY");
            output.add(new ParsedRate(new FxRateProvider.Rate(
                    currency,"KZT",rate,nominal,effective,"NBRK"),"NBRK_RSS"));
        }
        return List.copyOf(output);
    }

    private static LocalDate date(String value) {
        for(DateTimeFormatter formatter:List.of(FOUR_YEAR,TWO_YEAR)) {
            try {return LocalDate.parse(value,formatter);}
            catch(DateTimeParseException ignored) { }
        }
        throw new IllegalArgumentException("NBRK_XML_DATE_INVALID");
    }

    private static Element singleChild(Element parent,String tag) {
        List<Element> matches=children(parent,tag);
        if(matches.size()!=1)
            throw new IllegalArgumentException("NBRK_XML_FIELD_REQUIRED_"+tag);
        return matches.getFirst();
    }

    private static List<Element> children(Element parent,String tag) {
        List<Element> result=new ArrayList<>();
        NodeList children=parent.getChildNodes();
        for(int i=0;i<children.getLength();i++) {
            Node node=children.item(i);
            if(node instanceof Element element && element.getTagName().equals(tag))
                result.add(element);
        }
        return result;
    }

    private static String text(Element parent,String tag) {
        Element child=singleChild(parent,tag);
        if(child.getChildNodes().getLength()!=1
                || child.getFirstChild().getNodeType()!=Node.TEXT_NODE)
            throw new IllegalArgumentException("NBRK_XML_FIELD_INVALID_"+tag);
        return child.getTextContent();
    }
}
