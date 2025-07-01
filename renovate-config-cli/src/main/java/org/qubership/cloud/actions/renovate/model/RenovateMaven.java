package org.qubership.cloud.actions.renovate.model;

import lombok.Data;

import java.util.List;

@Data
public class RenovateMaven {
    List<RenovateMavenRepository> repositories;
}
