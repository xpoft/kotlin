/*
 * Copyright 2010-2012 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package org.jetbrains.jet.di;

import org.jetbrains.jet.lang.types.lang.JetStandardLibrary;
import org.jetbrains.jet.lang.resolve.BindingContext;
import java.util.List;
import org.jetbrains.jet.lang.psi.JetFile;
import org.jetbrains.jet.codegen.JetTypeMapper;
import org.jetbrains.jet.lang.resolve.java.CompilerSpecialMode;
import org.jetbrains.jet.codegen.ClosureAnnotator;
import org.jetbrains.jet.lang.types.lang.JetStandardLibrary;
import org.jetbrains.jet.lang.resolve.BindingContext;
import java.util.List;
import org.jetbrains.jet.lang.psi.JetFile;
import org.jetbrains.annotations.NotNull;

/* This file is generated by org.jetbrains.jet.di.AllInjectorsGenerator. DO NOT EDIT! */
public class InjectorForJetTypeMapper {

    private JetTypeMapper jetTypeMapper;

    public InjectorForJetTypeMapper(
        @NotNull JetStandardLibrary jetStandardLibrary,
        @NotNull BindingContext bindingContext,
        @NotNull List<JetFile> listOfJetFile
    ) {
        this.jetTypeMapper = new JetTypeMapper();
        CompilerSpecialMode compilerSpecialMode = CompilerSpecialMode.REGULAR;
        ClosureAnnotator closureAnnotator = new ClosureAnnotator();

        this.jetTypeMapper.setBindingContext(bindingContext);
        this.jetTypeMapper.setClosureAnnotator(closureAnnotator);
        this.jetTypeMapper.setCompilerSpecialMode(compilerSpecialMode);
        this.jetTypeMapper.setStandardLibrary(jetStandardLibrary);

        closureAnnotator.setBindingContext(bindingContext);
        closureAnnotator.setFiles(listOfJetFile);

        jetTypeMapper.init();

        closureAnnotator.init();

    }

    public JetTypeMapper getJetTypeMapper() {
        return this.jetTypeMapper;
    }

}