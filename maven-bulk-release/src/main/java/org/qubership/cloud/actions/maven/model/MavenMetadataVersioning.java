package org.qubership.cloud.actions.maven.model;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import lombok.Data;

import java.util.List;

@Data
public class MavenMetadataVersioning {
    @JacksonXmlProperty(localName = "latest")
    public String latest;

    @JacksonXmlProperty(localName = "release")
    public String release;

    @JacksonXmlElementWrapper(localName = "versions")
    @JacksonXmlProperty(localName = "version")
    public List<String> versions;

    @JacksonXmlProperty(localName = "lastUpdated")
    public String lastUpdated;
}
