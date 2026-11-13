package com.gaterook.e2e.modules.fixture;

import com.gaterook.connector.api.Connector;
import com.gaterook.plugin.protocol.ModuleManifests;
import com.gaterook.plugin.protocol.PluginManifest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The fixture's connectors packed the two ways a connector reaches a core: as a module, and compiled in. */
public final class EchoPackages {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final List<String> CONNECTORS = List.of("EchoModule", "EchoModule$EchoDb", "EchoModule$EchoLog");

  private EchoPackages() {}

  /**
   * The module as it is published: its classes, the manifest written from its connectors, and the build it says it
   * is when it answers a call.
   */
  public static byte[] module(String version) throws IOException {
    List<Connector> connectors = new EchoModule().connectors(null);
    ObjectNode descriptor = (ObjectNode) JSON.readTree(EchoModule.DESCRIPTOR);
    descriptor.put("version", version);
    ObjectNode manifest = ModuleManifests.write(descriptor, connectors);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      entry(zip, PluginManifest.FILE, JSON.writeValueAsBytes(manifest));
      entry(zip, "META-INF/echo-build.txt", version.getBytes(StandardCharsets.UTF_8));
      classes(zip, CONNECTORS);
    }
    return bytes.toByteArray();
  }

  /**
   * The same connectors as part of a build: their classes and {@link EchoCompiledIn}, named where Spring Boot looks
   * for auto-configurations. A core started with this jar among its own classes has the types compiled in.
   */
  public static byte[] compiledIn() throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      entry(zip, "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports",
          (EchoCompiledIn.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));
      classes(zip, List.of("EchoModule", "EchoModule$EchoDb", "EchoModule$EchoLog", "EchoCompiledIn"));
    }
    return bytes.toByteArray();
  }

  private static void classes(ZipOutputStream zip, List<String> names) throws IOException {
    String folder = EchoModule.class.getPackageName().replace('.', '/') + "/";
    for (String name : names) {
      try (InputStream in = EchoModule.class.getResourceAsStream("/" + folder + name + ".class")) {
        entry(zip, folder + name + ".class", in.readAllBytes());
      }
    }
  }

  private static void entry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(bytes);
    zip.closeEntry();
  }
}
