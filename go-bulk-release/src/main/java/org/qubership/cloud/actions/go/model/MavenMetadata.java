package org.qubership.cloud.actions.go.model;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import lombok.Data;

@Data
public class MavenMetadata {

    @JacksonXmlProperty(localName = "groupId")
    public String groupId;

    @JacksonXmlProperty(localName = "artifactId")
    public String artifactId;

    @JacksonXmlProperty(localName = "versioning")
    public MavenMetadataVersioning versioning;

}
