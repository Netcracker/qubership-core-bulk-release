package org.qubership.cloud.actions.go.model.gomod;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class GoDependency {
    private String module;
    private String version;
}
