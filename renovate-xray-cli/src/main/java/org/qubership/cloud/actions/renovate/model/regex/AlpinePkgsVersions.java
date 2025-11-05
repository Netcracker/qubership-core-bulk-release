package org.qubership.cloud.actions.renovate.model.regex;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AlpinePkgsVersions {
    List<AlpinePkgVersion> results;

    //{
    //    "results": [
    //        {
    //            "repo": "dl_cdn_alpinelinux_org_alpine.apk.proxy-cache",
    //            "path": "edge/community/x86_64",
    //            "name": "clang18-headers-18.1.8-r6.apk"
    //        }
    //    ],
    //    "range": {
    //        "start_pos": 0,
    //        "end_pos": 24,
    //        "total": 24
    //    }
    //}
}
