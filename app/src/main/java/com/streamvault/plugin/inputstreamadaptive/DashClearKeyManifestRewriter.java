package com.streamvault.plugin.inputstreamadaptive;

import android.util.Base64;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

final class DashClearKeyManifestRewriter {
    private static final String CENC_NS = "urn:mpeg:cenc:2013";
    private static final String CLEARKEY_SCHEME = "urn:uuid:e2719d58-a985-b3c9-781a-b030af78d30e";
    private static final byte[] CLEARKEY_SYSTEM_ID = hex("e2719d58a985b3c9781ab030af78d30e");

    private DashClearKeyManifestRewriter() {
    }

    static String rewrite(String manifest, List<AdaptiveChannel.ClearKey> keys) {
        if (manifest == null || manifest.isEmpty() || keys == null || keys.isEmpty()) {
            return manifest == null ? "" : manifest;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            disableExternalEntities(factory);
            Document document = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(manifest.getBytes(StandardCharsets.UTF_8)));

            String pssh = clearKeyPssh(keys);
            String defaultKid = defaultKidAttribute(keys);
            int inserted = 0;
            NodeList adaptationSets = document.getElementsByTagNameNS("*", "AdaptationSet");
            for (int i = 0; i < adaptationSets.getLength(); i++) {
                Node node = adaptationSets.item(i);
                if (!(node instanceof Element)) continue;
                Element adaptationSet = (Element) node;
                if (hasClearKeyContentProtection(adaptationSet)) continue;
                if (!hasEncryptedContentProtection(adaptationSet)) continue;
                insertClearKeyContentProtection(document, adaptationSet, pssh, defaultKid);
                inserted++;
            }

            if (inserted == 0) {
                for (int i = 0; i < adaptationSets.getLength(); i++) {
                    Node node = adaptationSets.item(i);
                    if (!(node instanceof Element)) continue;
                    Element adaptationSet = (Element) node;
                    if (hasClearKeyContentProtection(adaptationSet)) continue;
                    insertClearKeyContentProtection(document, adaptationSet, pssh, defaultKid);
                    inserted++;
                }
            }

            if (inserted == 0) return manifest;
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(document), new StreamResult(output));
            return output.toString("UTF-8");
        } catch (Exception ignored) {
            return manifest;
        }
    }

    private static void insertClearKeyContentProtection(
            Document document,
            Element parent,
            String pssh,
            String defaultKid
    ) {
        String namespace = parent.getNamespaceURI();
        Element contentProtection = namespace == null || namespace.isEmpty()
                ? document.createElement("ContentProtection")
                : document.createElementNS(namespace, "ContentProtection");
        contentProtection.setAttribute("schemeIdUri", CLEARKEY_SCHEME);
        contentProtection.setAttribute("value", "ClearKey");
        if (!defaultKid.isEmpty()) {
            contentProtection.setAttributeNS(CENC_NS, "cenc:default_KID", defaultKid);
        }
        Element psshElement = document.createElementNS(CENC_NS, "cenc:pssh");
        psshElement.setTextContent(pssh);
        contentProtection.appendChild(psshElement);

        Node insertBefore = firstNonContentProtectionChild(parent);
        parent.insertBefore(contentProtection, insertBefore);
    }

    private static Node firstNonContentProtectionChild(Element parent) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element && !"ContentProtection".equals(child.getLocalName())) {
                return child;
            }
        }
        return null;
    }

    private static boolean hasClearKeyContentProtection(Element parent) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element element = (Element) child;
            if (!"ContentProtection".equals(element.getLocalName())) continue;
            String scheme = element.getAttribute("schemeIdUri").toLowerCase(Locale.ROOT);
            if (CLEARKEY_SCHEME.equals(scheme)) return true;
        }
        return false;
    }

    private static boolean hasEncryptedContentProtection(Element parent) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element element = (Element) child;
            if (!"ContentProtection".equals(element.getLocalName())) continue;
            String scheme = element.getAttribute("schemeIdUri").toLowerCase(Locale.ROOT);
            if (scheme.contains("mp4protection") || scheme.startsWith("urn:uuid:")) return true;
        }
        return false;
    }

    private static String clearKeyPssh(List<AdaptiveChannel.ClearKey> keys) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int size = 4 + 4 + 4 + 16 + 4 + (keys.size() * 16) + 4;
        writeInt(output, size);
        output.write('p');
        output.write('s');
        output.write('s');
        output.write('h');
        writeInt(output, 0x01000000);
        output.write(CLEARKEY_SYSTEM_ID, 0, CLEARKEY_SYSTEM_ID.length);
        writeInt(output, keys.size());
        for (AdaptiveChannel.ClearKey key : keys) {
            byte[] kid = hex(key.kidHex);
            output.write(kid, 0, kid.length);
        }
        writeInt(output, 0);
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
    }

    private static String defaultKidAttribute(List<AdaptiveChannel.ClearKey> keys) {
        StringBuilder builder = new StringBuilder();
        for (AdaptiveChannel.ClearKey key : keys) {
            if (builder.length() > 0) builder.append(' ');
            builder.append(formatUuid(key.kidHex));
        }
        return builder.toString();
    }

    private static String formatUuid(String hex) {
        String normalized = hex == null ? "" : hex.replace("-", "").toLowerCase(Locale.ROOT);
        if (normalized.length() != 32) return normalized;
        return normalized.substring(0, 8) + "-" +
                normalized.substring(8, 12) + "-" +
                normalized.substring(12, 16) + "-" +
                normalized.substring(16, 20) + "-" +
                normalized.substring(20);
    }

    private static void writeInt(ByteArrayOutputStream output, int value) {
        byte[] bytes = ByteBuffer.allocate(4).putInt(value).array();
        output.write(bytes, 0, bytes.length);
    }

    private static byte[] hex(String value) {
        String normalized = value == null ? "" : value.replace("-", "").trim();
        byte[] bytes = new byte[normalized.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(normalized.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    private static void disableExternalEntities(DocumentBuilderFactory factory) {
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (Exception ignored) {
        }
        try {
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        } catch (Exception ignored) {
        }
        try {
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (Exception ignored) {
        }
    }
}
