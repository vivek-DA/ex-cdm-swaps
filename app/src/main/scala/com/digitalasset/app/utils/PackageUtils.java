/*
 * Copyright 2019 Digital Asset (Switzerland) GmbH and/or its affiliates
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.digitalasset.app.utils;

import com.daml.daml_lf_dev.DamlLf1;
import java.util.List;
import java.util.stream.Collectors;

public class PackageUtils {

  private static DamlLf1.DottedName getInternedName(int internedNameId, DamlLf1.Package lfPackage) {
    DamlLf1.InternedDottedName internedDottedModuleName =
        lfPackage.getInternedDottedNames(internedNameId);
    List<String> actualModuleNameList =
        internedDottedModuleName
            .getSegmentsInternedStrList()
            .stream()
            .map(lfPackage::getInternedStrings)
            .collect(Collectors.toList());
    return DamlLf1.DottedName.newBuilder().addAllSegments(actualModuleNameList).build();
  }

  public static DamlLf1.DottedName getModuleName(DamlLf1.Module mod, DamlLf1.Package lfPackage) {
    DamlLf1.DottedName modN;
    if (mod.hasNameDname()) { // DamlLf version <= 1.6 or nameCase_ == 1
      modN = mod.getNameDname();
    } else {
      modN = getInternedName(mod.getNameInternedDname(), lfPackage);
    }
    return modN;
  }

  public static DamlLf1.DottedName getDataTypeName(
      DamlLf1.DefDataType dataType, DamlLf1.Package lfPackage) {
    DamlLf1.DottedName dataN;
    if (dataType.hasNameDname()) { // DamlLf version <= 1.6 or nameCase_ == 1
      dataN = dataType.getNameDname();
    } else {
      dataN = getInternedName(dataType.getNameInternedDname(), lfPackage);
    }
    return dataN;
  }

  public static DamlLf1.DottedName getTypeConName(
      DamlLf1.TypeConName tycon, DamlLf1.Package lfPackage) {
    DamlLf1.DottedName tyconN;
    if (tycon.hasNameDname()) { // DamlLf version <= 1.6 or nameCase_ == 2
      tyconN = tycon.getNameDname();
    } else {
      tyconN = getInternedName(tycon.getNameInternedDname(), lfPackage);
    }
    return tyconN;
  }

  public static DamlLf1.DottedName getDefTemplateName(
      DamlLf1.DefTemplate dt, DamlLf1.Package lfPackage) {
    DamlLf1.DottedName dtN;
    if (dt.hasTyconDname()) { // DamlLf version <= 1.6 or nameCase_ == 1
      dtN = dt.getTyconDname();
    } else {
      dtN = getInternedName(dt.getTyconInternedDname(), lfPackage);
    }
    return dtN;
  }

}
