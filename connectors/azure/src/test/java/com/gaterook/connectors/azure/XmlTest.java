package com.gaterook.connectors.azure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Azure Storage's XML as data: entities of the document itself never expand, a file or URL is never read. */
class XmlTest {

  private static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void readsElementsTextAndThePredefinedEntities() {
    Xml.Node root = Xml.parse(bytes("﻿<?xml version=\"1.0\" encoding=\"utf-8\"?><EnumerationResults><Blobs>"
        + "<Blob><Name>a &amp; b &#x263A;.txt</Name><Properties><Content-Length>7</Content-Length></Properties>"
        + "</Blob></Blobs><NextMarker/></EnumerationResults>"));

    assertThat(root.name()).isEqualTo("EnumerationResults");
    Xml.Node blob = root.child("Blobs").all("Blob").getFirst();
    assertThat(blob.text("Name")).isEqualTo("a & b ☺.txt");
    assertThat(blob.text("Properties/Content-Length")).isEqualTo("7");
    assertThat(root.text("NextMarker")).isEmpty();
    assertThat(root.text("Missing/Path")).isNull();
  }

  @Test
  void aDocumentTypeOrEntityOfItsOwnIsRefused() {
    assertThatThrownBy(() -> Xml.parse(bytes("<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM "
        + "\"file:///etc/hosts\">]><r>&x;</r>"))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Xml.parse(bytes("<!DOCTYPE r [<!ENTITY a \"aaaaaaaaaa\"><!ENTITY b \"&a;&a;&a;&a;\">]>"
        + "<r>&b;</r>"))).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void deepOrBrokenDocumentsAreRefused() {
    assertThatThrownBy(() -> Xml.parse(bytes("<a>".repeat(40) + "</a>".repeat(40))))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("deeper");
    assertThatThrownBy(() -> Xml.parse(bytes("<a><b></a>"))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Xml.parse(bytes(""))).isInstanceOf(IllegalArgumentException.class);
  }
}
