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

package org.jetbrains.jet.lang.resolve.lazy;

import com.google.common.collect.Lists;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jet.lang.descriptors.*;
import org.jetbrains.jet.lang.diagnostics.Errors;
import org.jetbrains.jet.lang.psi.*;
import org.jetbrains.jet.lang.resolve.*;
import org.jetbrains.jet.lang.resolve.lazy.data.JetClassLikeInfo;
import org.jetbrains.jet.lang.resolve.name.Name;
import org.jetbrains.jet.lang.resolve.scopes.JetScope;
import org.jetbrains.jet.lang.resolve.scopes.receivers.ReceiverDescriptor;
import org.jetbrains.jet.lang.types.DeferredType;
import org.jetbrains.jet.lang.types.JetType;
import org.jetbrains.jet.util.lazy.LazyValue;

import java.util.*;

/**
* @author abreslav
*/
public class LazyClassMemberScope extends AbstractLazyMemberScope<LazyClassDescriptor, ClassMemberDeclarationProvider> {

    private interface MemberExtractor<T extends CallableMemberDescriptor> {
        MemberExtractor<FunctionDescriptor> EXTRACT_FUNCTIONS = new MemberExtractor<FunctionDescriptor>() {
            @NotNull
            @Override
            public Collection<FunctionDescriptor> extract(@NotNull JetType extractFrom, @NotNull Name name) {
                return extractFrom.getMemberScope().getFunctions(name);
            }
        };

        MemberExtractor<PropertyDescriptor> EXTRACT_PROPERTIES = new MemberExtractor<PropertyDescriptor>() {
            @NotNull
            @Override
            public Collection<PropertyDescriptor> extract(@NotNull JetType extractFrom, @NotNull Name name) {
                //noinspection unchecked
                return (Collection) extractFrom.getMemberScope().getProperties(name);
            }
        };

        @NotNull
        Collection<T> extract(@NotNull JetType extractFrom, @NotNull Name name);
    }

    private ConstructorDescriptor primaryConstructor;
    private boolean primaryConstructorResolved = false;

    public LazyClassMemberScope(
            @NotNull ResolveSession resolveSession,
            @NotNull ClassMemberDeclarationProvider declarationProvider,
            @NotNull LazyClassDescriptor thisClass
    ) {
        super(resolveSession, declarationProvider, thisClass);
    }

    @NotNull
    @Override
    protected JetScope getScopeForMemberDeclarationResolution(JetDeclaration declaration) {
        if (declaration instanceof JetProperty) {
            return thisDescriptor.getScopeForPropertyInitializerResolution();
        }
        return thisDescriptor.getScopeForMemberDeclarationResolution();
    }

    private <D extends CallableMemberDescriptor> void generateFakeOverrides(
            @NotNull Name name,
            @NotNull Collection<D> fromSupertypes,
            @NotNull final Collection<D> result,
            @NotNull final Class<? extends D> exactDescriptorClass
    ) {
        OverrideResolver.generateOverridesInFunctionGroup(
                name,
                fromSupertypes,
                Lists.newArrayList(result),
                thisDescriptor,
                new OverrideResolver.DescriptorSink() {
                    @Override
                    public void addToScope(@NotNull CallableMemberDescriptor fakeOverride) {
                        assert exactDescriptorClass.isInstance(fakeOverride) : "Wrong descriptor type in an override: " +
                                                                               fakeOverride +
                                                                               " while expecting " +
                                                                               exactDescriptorClass.getSimpleName();
                        //noinspection unchecked
                        result.add((D) fakeOverride);
                    }

                    @Override
                    public void conflict(@NotNull CallableMemberDescriptor fromSuper, @NotNull CallableMemberDescriptor fromCurrent) {
                        BindingTrace trace = resolveSession.getTrace();
                        JetDeclaration declaration = (JetDeclaration) BindingContextUtils.descriptorToDeclaration(trace.getBindingContext(),
                                                                                                                  fromCurrent);
                        assert declaration != null : "fromCurrent can not be a fake override";
                        trace.report(Errors.CONFLICTING_OVERLOADS
                                             .on(declaration, fromCurrent, fromCurrent.getContainingDeclaration().getName().getName()));
                    }
                }
        );

    }

    @NotNull
    @Override
    public Set<FunctionDescriptor> getFunctions(@NotNull Name name) {
        // TODO: this should be handled by lazy function descriptors
        Set<FunctionDescriptor> functions = super.getFunctions(name);
        for (FunctionDescriptor functionDescriptor : functions) {
            if (functionDescriptor.getKind() == CallableMemberDescriptor.Kind.FAKE_OVERRIDE) continue;
            PsiElement element =
                    BindingContextUtils.callableDescriptorToDeclaration(resolveSession.getTrace().getBindingContext(), functionDescriptor);
            OverrideResolver.resolveUnknownVisibilityForMember((JetDeclaration) element, functionDescriptor, resolveSession.getTrace());
        }
        return functions;
    }

    @Override
    protected void getNonDeclaredFunctions(@NotNull Name name, @NotNull final Set<FunctionDescriptor> result) {
        Collection<FunctionDescriptor> fromSupertypes = Lists.newArrayList();
        for (JetType supertype : thisDescriptor.getTypeConstructor().getSupertypes()) {
            fromSupertypes.addAll(supertype.getMemberScope().getFunctions(name));
        }
        generateDelegatingDescriptors(name, MemberExtractor.EXTRACT_FUNCTIONS, result);
        generateEnumClassObjectMethods(result, name);
        generateFakeOverrides(name, fromSupertypes, result, FunctionDescriptor.class);
    }

    private void generateEnumClassObjectMethods(@NotNull Collection<? super FunctionDescriptor> result, @NotNull Name name) {
        if (!isEnumClassObject()) return;

        ClassDescriptor classDescriptor = (ClassDescriptor) thisDescriptor.getContainingDeclaration();

        if (name.equals(DescriptorResolver.VALUES_METHOD_NAME)) {
            SimpleFunctionDescriptor valuesMethod = DescriptorResolver
                    .createEnumClassObjectValuesMethod(thisDescriptor, resolveSession.getTrace());
            result.add(valuesMethod);
        }
        else if (name.equals(DescriptorResolver.VALUE_OF_METHOD_NAME)) {
            SimpleFunctionDescriptor valueOfMethod = DescriptorResolver
                    .createEnumClassObjectValueOfMethod(thisDescriptor, resolveSession.getTrace());
            result.add(valueOfMethod);
        }
    }

    private boolean isEnumClassObject() {
        DeclarationDescriptor containingDeclaration = thisDescriptor.getContainingDeclaration();
        if (!(containingDeclaration instanceof ClassDescriptor)) return false;
        ClassDescriptor classDescriptor = (ClassDescriptor) containingDeclaration;
        if (classDescriptor.getKind() != ClassKind.ENUM_CLASS) return false;
        if (classDescriptor.getClassObjectDescriptor() != thisDescriptor) return false;
        return true;
    }

    @NotNull
    @Override
    public Set<VariableDescriptor> getProperties(@NotNull Name name) {
        // TODO: this should be handled by lazy property descriptors
        Set<VariableDescriptor> properties = super.getProperties(name);
        for (VariableDescriptor variableDescriptor : properties) {
            PropertyDescriptor propertyDescriptor = (PropertyDescriptor) variableDescriptor;
            if (propertyDescriptor.getKind() == CallableMemberDescriptor.Kind.FAKE_OVERRIDE) continue;
            PsiElement element =
                    BindingContextUtils.callableDescriptorToDeclaration(resolveSession.getTrace().getBindingContext(), propertyDescriptor);
            OverrideResolver.resolveUnknownVisibilityForMember((JetDeclaration) element, propertyDescriptor, resolveSession.getTrace());
        }
        return properties;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void getNonDeclaredProperties(@NotNull Name name, @NotNull final Set<VariableDescriptor> result) {
        JetClassLikeInfo classInfo = declarationProvider.getOwnerInfo();

        // From primary constructor parameters
        ConstructorDescriptor primaryConstructor = getPrimaryConstructor();
        if (primaryConstructor != null) {
            List<ValueParameterDescriptor> valueParameterDescriptors = primaryConstructor.getValueParameters();
            List<? extends JetParameter> primaryConstructorParameters = classInfo.getPrimaryConstructorParameters();
            assert valueParameterDescriptors.size() == primaryConstructorParameters.size() : "From descriptor: " + valueParameterDescriptors.size() + " but from PSI: " + primaryConstructorParameters.size();
            for (ValueParameterDescriptor valueParameterDescriptor : valueParameterDescriptors) {
                JetParameter parameter = primaryConstructorParameters.get(valueParameterDescriptor.getIndex());
                if (parameter.getValOrVarNode() != null && name.equals(parameter.getNameAsName())) {
                    PropertyDescriptor propertyDescriptor =
                            resolveSession.getInjector().getDescriptorResolver().resolvePrimaryConstructorParameterToAProperty(
                                    thisDescriptor,
                                    valueParameterDescriptor,
                                    thisDescriptor.getScopeForClassHeaderResolution(),
                                    parameter, resolveSession.getTrace()
                            );
                    result.add(propertyDescriptor);
                }
            }
        }

        // Members from supertypes
        Collection<PropertyDescriptor> fromSupertypes = Lists.newArrayList();
        for (JetType supertype : thisDescriptor.getTypeConstructor().getSupertypes()) {
            fromSupertypes.addAll((Set) supertype.getMemberScope().getProperties(name));
        }
        generateDelegatingDescriptors(name, MemberExtractor.EXTRACT_PROPERTIES, result);
        generateFakeOverrides(name, fromSupertypes, (Set) result, PropertyDescriptor.class);
    }

    private <T extends CallableMemberDescriptor> void generateDelegatingDescriptors(
            @NotNull Name name,
            @NotNull MemberExtractor<T> extractor,
            @NotNull Set<? super T> result
    ) {
        for (JetDelegationSpecifier delegationSpecifier : declarationProvider.getOwnerInfo().getDelegationSpecifiers()) {
            if (delegationSpecifier instanceof JetDelegatorByExpressionSpecifier) {
                JetDelegatorByExpressionSpecifier specifier = (JetDelegatorByExpressionSpecifier) delegationSpecifier;
                JetTypeReference typeReference = specifier.getTypeReference();
                if (typeReference != null) {
                    JetType supertype = resolveSession.getInjector().getTypeResolver().resolveType(
                            thisDescriptor.getScopeForClassHeaderResolution(),
                            typeReference,
                            resolveSession.getTrace(),
                            false);
                    Collection<T> descriptors =
                            DelegationResolver.generateDelegatedMembers(thisDescriptor, extractor.extract(supertype, name));
                    result.addAll(descriptors);
                }
            }
        }
    }
    @Override
    protected void addExtraDescriptors() {
        for (JetType supertype : thisDescriptor.getTypeConstructor().getSupertypes()) {
            for (DeclarationDescriptor descriptor : supertype.getMemberScope().getAllDescriptors()) {
                if (descriptor instanceof FunctionDescriptor) {
                    getFunctions(descriptor.getName());
                }
                else if (descriptor instanceof PropertyDescriptor) {
                    getProperties(descriptor.getName());
                }
                // Nothing else is inherited
            }
        }

        getFunctions(DescriptorResolver.VALUES_METHOD_NAME);
        getFunctions(DescriptorResolver.VALUE_OF_METHOD_NAME);
    }

    @Override
    public NamespaceDescriptor getNamespace(@NotNull Name name) {
        return null;
    }

    @NotNull
    @Override
    public ReceiverDescriptor getImplicitReceiver() {
        return thisDescriptor.getImplicitReceiver();
    }

    @NotNull
    public Set<ConstructorDescriptor> getConstructors() {
        ConstructorDescriptor constructor = getPrimaryConstructor();
        return constructor == null ? Collections.<ConstructorDescriptor>emptySet() : Collections.singleton(constructor);
    }

    @Nullable
    public ConstructorDescriptor getPrimaryConstructor() {
        if (!primaryConstructorResolved) {
            Set<ClassKind> generateConstructorsFor =
                    EnumSet.of(ClassKind.CLASS, ClassKind.ANNOTATION_CLASS, ClassKind.OBJECT, ClassKind.ENUM_CLASS, ClassKind.ENUM_ENTRY);
            if (generateConstructorsFor.contains(thisDescriptor.getKind())) {
                JetClassOrObject classOrObject = declarationProvider.getOwnerInfo().getCorrespondingClassOrObject();
                if (
                        thisDescriptor.getKind() != ClassKind.OBJECT // a fake class object of an enum class
                        && !declaresObjectOrEnumConstant(classOrObject) // normal objects and enum entries with no constructors
                ) {
                    JetClass jetClass = (JetClass) classOrObject;
                    ConstructorDescriptorImpl constructor = resolveSession.getInjector().getDescriptorResolver()
                            .resolvePrimaryConstructorDescriptor(thisDescriptor.getScopeForClassHeaderResolution(),
                                                                 thisDescriptor,
                                                                 jetClass,
                                                                 resolveSession.getTrace());
                    primaryConstructor = constructor;
                    setDeferredReturnType(constructor);
                }
                else {
                    ConstructorDescriptorImpl constructor =
                            DescriptorResolver.createPrimaryConstructorForObject(classOrObject, thisDescriptor, resolveSession.getTrace());
                    setDeferredReturnType(constructor);
                    primaryConstructor = constructor;
                }
            }
            primaryConstructorResolved = true;
        }
        return primaryConstructor;
    }

    private void setDeferredReturnType(@NotNull ConstructorDescriptorImpl descriptor) {
        descriptor.setReturnType(DeferredType.create(resolveSession.getTrace(), new LazyValue<JetType>() {
            @Override
            protected JetType compute() {
                return thisDescriptor.getDefaultType();
            }
        }));
    }

    @Override
    public String toString() {
        // Do not add details here, they may compromise the laziness during debugging
        return "lazy scope for class " + thisDescriptor.getName();
    }
}
