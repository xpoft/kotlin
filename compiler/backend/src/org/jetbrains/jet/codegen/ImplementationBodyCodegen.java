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

package org.jetbrains.jet.codegen;

import com.google.common.collect.Lists;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.util.Pair;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.asm4.AnnotationVisitor;
import org.jetbrains.asm4.MethodVisitor;
import org.jetbrains.asm4.Type;
import org.jetbrains.asm4.commons.InstructionAdapter;
import org.jetbrains.asm4.commons.Method;
import org.jetbrains.jet.codegen.signature.*;
import org.jetbrains.jet.codegen.signature.kotlin.JetMethodAnnotationWriter;
import org.jetbrains.jet.codegen.signature.kotlin.JetValueParameterAnnotationWriter;
import org.jetbrains.jet.lang.descriptors.*;
import org.jetbrains.jet.lang.psi.*;
import org.jetbrains.jet.lang.resolve.BindingContext;
import org.jetbrains.jet.lang.resolve.BindingContextUtils;
import org.jetbrains.jet.lang.resolve.OverridingUtil;
import org.jetbrains.jet.lang.resolve.constants.CompileTimeConstant;
import org.jetbrains.jet.lang.resolve.java.JdkNames;
import org.jetbrains.jet.lang.resolve.java.JvmAbi;
import org.jetbrains.jet.lang.resolve.java.JvmClassName;
import org.jetbrains.jet.lang.resolve.java.JvmStdlibNames;
import org.jetbrains.jet.lang.types.JetType;
import org.jetbrains.jet.lang.types.lang.JetStandardLibrary;
import org.jetbrains.jet.lexer.JetTokens;
import org.jetbrains.jet.utils.BitSetUtils;

import java.util.*;

import static org.jetbrains.asm4.Opcodes.*;
import static org.jetbrains.jet.codegen.JetTypeMapper.TYPE_OBJECT;

/**
 * @author max
 * @author yole
 * @author alex.tkachman
 */
public class ImplementationBodyCodegen extends ClassBodyCodegen {
    private static final String VALUES = "$VALUES";
    private JetDelegationSpecifier superCall;
    private String superClass;
    @Nullable // null means java/lang/Object
    private JetType superClassType;
    private final JetTypeMapper typeMapper;
    private final BindingContext bindingContext;

    public ImplementationBodyCodegen(JetClassOrObject aClass, CodegenContext context, ClassBuilder v, GenerationState state) {
        super(aClass, context, v, state);
        typeMapper = state.getInjector().getJetTypeMapper();
        bindingContext = state.getBindingContext();
    }

    @Override
    protected void generateDeclaration() {
        getSuperClass();

        JvmClassSignature signature = signature();

        boolean isAbstract = false;
        boolean isInterface = false;
        boolean isFinal = false;
        boolean isStatic = false;
        boolean isAnnotation = false;
        boolean isEnum = false;

        if (myClass instanceof JetClass) {
            JetClass jetClass = (JetClass) myClass;
            if (jetClass.hasModifier(JetTokens.ABSTRACT_KEYWORD)) {
                isAbstract = true;
            }
            if (jetClass.isTrait()) {
                isAbstract = true;
                isInterface = true;
            }
            else if (jetClass.isAnnotation()) {
                isAbstract = true;
                isInterface = true;
                isAnnotation = true;
                signature.getInterfaces().add(JdkNames.JLA_ANNOTATION.getInternalName());
            }
            else if (jetClass.hasModifier(JetTokens.ENUM_KEYWORD)) {
                isEnum = true;
            }

            if (!jetClass.hasModifier(JetTokens.OPEN_KEYWORD) && !isAbstract) {
                isFinal = true;
            }
        }
        else if (myClass.getParent() instanceof JetClassObject) {
            isStatic = true;
        }

        int access = 0;
        access |= ACC_PUBLIC;
        if (isAbstract) {
            access |= ACC_ABSTRACT;
        }
        if (isInterface) {
            access |= ACC_INTERFACE; // ACC_SUPER
        }
        else {
            access |= ACC_SUPER;
        }
        if (isFinal) {
            access |= ACC_FINAL;
        }
        if (isStatic) {
            access |= ACC_STATIC;
        }
        if (isAnnotation) {
            access |= ACC_ANNOTATION;
        }
        if (isEnum) {
            for (JetDeclaration declaration : myClass.getDeclarations()) {
                if (declaration instanceof JetEnumEntry) {
                    if (state.getInjector().getClosureAnnotator().enumEntryNeedSubclass((JetEnumEntry) declaration)) {
                        access &= ~ACC_FINAL;
                    }
                }
            }
            access |= ACC_ENUM;
        }
        List<String> interfaces = signature.getInterfaces();
        v.defineClass(myClass, V1_6,
                      access,
                      signature.getName(),
                      signature.getJavaGenericSignature(),
                      signature.getSuperclassName(),
                      interfaces.toArray(new String[interfaces.size()])
        );
        v.visitSource(myClass.getContainingFile().getName(), null);

        writeInnerOuterClasses();

        AnnotationCodegen.forClass(v.getVisitor(), typeMapper).genAnnotations(descriptor);

        writeClassSignatureIfNeeded(signature);
    }

    private void writeInnerOuterClasses() {
        ClassDescriptor container = getContainingClassDescriptor(descriptor);
        if (container != null) {
            v.visitOuterClass(typeMapper.mapType(container.getDefaultType(), MapTypeMode.IMPL).getInternalName(), null, null);
        }

        for (DeclarationDescriptor declarationDescriptor : descriptor.getUnsubstitutedInnerClassesScope().getAllDescriptors()) {
            assert declarationDescriptor instanceof ClassDescriptor;
            ClassDescriptor innerClass = (ClassDescriptor) declarationDescriptor;
            // TODO: proper access
            int innerClassAccess = ACC_PUBLIC;
            if (innerClass.getModality() == Modality.FINAL) {
                innerClassAccess |= ACC_FINAL;
            }
            else if (innerClass.getModality() == Modality.ABSTRACT) {
                innerClassAccess |= ACC_ABSTRACT;
            }

            if (innerClass.getKind() == ClassKind.TRAIT) {
                innerClassAccess |= ACC_INTERFACE;
            }

            // TODO: cache internal names
            String outerClassInernalName = typeMapper.mapType(descriptor.getDefaultType(), MapTypeMode.IMPL).getInternalName();
            String innerClassInternalName = typeMapper.mapType(innerClass.getDefaultType(), MapTypeMode.IMPL).getInternalName();
            v.visitInnerClass(innerClassInternalName, outerClassInernalName, innerClass.getName().getName(), innerClassAccess);
        }

        if (descriptor.getClassObjectDescriptor() != null) {
            int innerClassAccess = ACC_PUBLIC | ACC_FINAL | ACC_STATIC;
            String outerClassInernalName = typeMapper.mapType(descriptor.getDefaultType(), MapTypeMode.IMPL).getInternalName();
            v.visitInnerClass(outerClassInernalName + JvmAbi.CLASS_OBJECT_SUFFIX, outerClassInernalName, JvmAbi.CLASS_OBJECT_CLASS_NAME,
                              innerClassAccess);
        }
    }

    private void writeClassSignatureIfNeeded(JvmClassSignature signature) {
        if (signature.getKotlinGenericSignature() != null || descriptor.getVisibility() != Visibilities.PUBLIC) {
            AnnotationVisitor annotationVisitor = v.newAnnotation(JvmStdlibNames.JET_CLASS.getDescriptor(), true);
            annotationVisitor.visit(JvmStdlibNames.JET_CLASS_SIGNATURE, signature.getKotlinGenericSignature());
            BitSet flags = CodegenUtil.getFlagsForVisibility(descriptor.getVisibility());
            int flagsValue = BitSetUtils.toInt(flags);
            if (JvmStdlibNames.FLAGS_DEFAULT_VALUE != flagsValue) {
                annotationVisitor.visit(JvmStdlibNames.JET_CLASS_FLAGS_FIELD, flagsValue);
            }
            annotationVisitor.visitEnd();
        }
    }

    @Nullable
    private static ClassDescriptor getContainingClassDescriptor(ClassDescriptor decl) {
        DeclarationDescriptor container = decl.getContainingDeclaration();
        while (container != null && !(container instanceof NamespaceDescriptor)) {
            if (container instanceof ClassDescriptor) return (ClassDescriptor) container;
            container = container.getContainingDeclaration();
        }
        return null;
    }

    private JvmClassSignature signature() {
        List<String> superInterfaces;

        LinkedHashSet<String> superInterfacesLinkedHashSet = new LinkedHashSet<String>();

        // TODO: generics signature is not always needed
        BothSignatureWriter signatureVisitor = new BothSignatureWriter(BothSignatureWriter.Mode.CLASS, true);


        {   // type parameters
            List<TypeParameterDescriptor> typeParameters = descriptor.getTypeConstructor().getParameters();
            typeMapper.writeFormalTypeParameters(typeParameters, signatureVisitor);
        }

        signatureVisitor.writeSupersStart();

        {   // superclass
            signatureVisitor.writeSuperclass();
            if (superClassType == null) {
                signatureVisitor.writeClassBegin(superClass, false, false);
                signatureVisitor.writeClassEnd();
            }
            else {
                typeMapper.mapType(superClassType, signatureVisitor, MapTypeMode.TYPE_PARAMETER);
            }
            signatureVisitor.writeSuperclassEnd();
        }


        {   // superinterfaces
            superInterfacesLinkedHashSet.add(JvmStdlibNames.JET_OBJECT.getInternalName());

            for (JetDelegationSpecifier specifier : myClass.getDelegationSpecifiers()) {
                JetType superType = bindingContext.get(BindingContext.TYPE, specifier.getTypeReference());
                assert superType != null;
                ClassDescriptor superClassDescriptor = (ClassDescriptor) superType.getConstructor().getDeclarationDescriptor();
                if (CodegenUtil.isInterface(superClassDescriptor)) {
                    signatureVisitor.writeInterface();
                    Type jvmName = typeMapper.mapType(superType, signatureVisitor, MapTypeMode.TYPE_PARAMETER);
                    signatureVisitor.writeInterfaceEnd();
                    superInterfacesLinkedHashSet.add(jvmName.getInternalName());
                }
            }

            superInterfaces = new ArrayList<String>(superInterfacesLinkedHashSet);
        }

        signatureVisitor.writeSupersEnd();

        return new JvmClassSignature(jvmName(), superClass, superInterfaces, signatureVisitor.makeJavaString(),
                                     signatureVisitor.makeKotlinClassSignature());
    }

    private String jvmName() {
        if (kind != OwnerKind.IMPLEMENTATION) {
            throw new IllegalStateException("must not call this method with kind " + kind);
        }
        return typeMapper.mapType(descriptor.getDefaultType(), MapTypeMode.IMPL).getInternalName();
    }

    protected void getSuperClass() {
        superClass = "java/lang/Object";
        superClassType = null;

        List<JetDelegationSpecifier> delegationSpecifiers = myClass.getDelegationSpecifiers();

        if (myClass instanceof JetClass && ((JetClass) myClass).isTrait()) {
            return;
        }

        if (kind != OwnerKind.IMPLEMENTATION) {
            throw new IllegalStateException("must be impl to reach this code: " + kind);
        }

        for (JetDelegationSpecifier specifier : delegationSpecifiers) {
            if (specifier instanceof JetDelegatorToSuperClass || specifier instanceof JetDelegatorToSuperCall) {
                JetType superType = bindingContext.get(BindingContext.TYPE, specifier.getTypeReference());
                assert superType != null;
                ClassDescriptor superClassDescriptor = (ClassDescriptor) superType.getConstructor().getDeclarationDescriptor();
                if (!CodegenUtil.isInterface(superClassDescriptor)) {
                    superClassType = superType;
                    assert superClassDescriptor != null;
                    superClass = typeMapper.mapType(superClassDescriptor.getDefaultType(), MapTypeMode.IMPL).getInternalName();
                    superCall = specifier;
                }
            }
        }

        if (superClassType == null) {
            if (descriptor.getKind() == ClassKind.ENUM_CLASS) {
                superClassType = JetStandardLibrary.getInstance().getEnumType(descriptor.getDefaultType());
                superClass = typeMapper.mapType(superClassType, MapTypeMode.VALUE).getInternalName();
            }
            if (descriptor.getKind() == ClassKind.ENUM_ENTRY) {
                superClassType = descriptor.getTypeConstructor().getSupertypes().iterator().next();
                superClass = typeMapper.mapType(superClassType, MapTypeMode.VALUE).getInternalName();
            }
        }
    }

    @Override
    protected void generateSyntheticParts() {
        generateFieldForObjectInstance();
        generateFieldForClassObject();

        try {
            generatePrimaryConstructor();
        }
        catch (CompilationException e) {
            throw e;
        }
        catch (ProcessCanceledException e) {
            throw e;
        }
        catch (RuntimeException e) {
            throw new RuntimeException("Error generating primary constructor of class " + myClass.getName() + " with kind " + kind, e);
        }

        generateTraitMethods();

        generateAccessors();

        generateEnumMethods();
    }

    private void generateEnumMethods() {
        if (myEnumConstants.size() > 0) {
            {
                Type type =
                        typeMapper.mapType(JetStandardLibrary.getInstance().getArrayType(descriptor.getDefaultType()), MapTypeMode.IMPL);

                MethodVisitor mv =
                        v.newMethod(myClass, ACC_PUBLIC | ACC_STATIC, "values", "()" + type.getDescriptor(), null, null);
                mv.visitCode();
                mv.visitFieldInsn(GETSTATIC, typeMapper.mapType(descriptor.getDefaultType(), MapTypeMode.VALUE).getInternalName(), VALUES,
                                  type.getDescriptor());
                mv.visitMethodInsn(INVOKEVIRTUAL, type.getInternalName(), "clone", "()Ljava/lang/Object;");
                mv.visitTypeInsn(CHECKCAST, type.getInternalName());
                mv.visitInsn(ARETURN);
                FunctionCodegen.endVisit(mv, "values()", myClass);
            }
            {
                Type type = typeMapper.mapType(descriptor.getDefaultType(), MapTypeMode.IMPL);

                MethodVisitor mv =
                        v.newMethod(myClass, ACC_PUBLIC | ACC_STATIC, "valueOf", "(Ljava/lang/String;)" + type.getDescriptor(), null, null);
                mv.visitCode();
                mv.visitLdcInsn(type);
                mv.visitVarInsn(ALOAD, 0);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Enum", "valueOf", "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;");
                mv.visitTypeInsn(CHECKCAST, type.getInternalName());
                mv.visitInsn(ARETURN);
                FunctionCodegen.endVisit(mv, "values()", myClass);
            }
        }
    }

    private void generateAccessors() {
        if (context.accessors != null) {
            for (Map.Entry<DeclarationDescriptor, DeclarationDescriptor> entry : context.accessors.entrySet()) {
                genAccessor(entry);
            }
        }
    }

    private void genAccessor(Map.Entry<DeclarationDescriptor, DeclarationDescriptor> entry) {
        if (entry.getValue() instanceof FunctionDescriptor) {
            FunctionDescriptor bridge = (FunctionDescriptor) entry.getValue();
            FunctionDescriptor original = (FunctionDescriptor) entry.getKey();

            Method method = typeMapper.mapSignature(bridge.getName(), bridge).getAsmMethod();
            Method originalMethod = typeMapper.mapSignature(original.getName(), original).getAsmMethod();
            Type[] argTypes = method.getArgumentTypes();

            String owner = typeMapper.getOwner(original, OwnerKind.IMPLEMENTATION).getInternalName();
            MethodVisitor mv = v.newMethod(null, ACC_BRIDGE | ACC_SYNTHETIC | ACC_STATIC, bridge.getName().getName(),
                                           method.getDescriptor(), null, null);
            if (state.getClassBuilderMode() == ClassBuilderMode.STUBS) {
                StubCodegen.generateStubCode(mv);
            }
            else if (state.getClassBuilderMode() == ClassBuilderMode.FULL) {
                mv.visitCode();

                InstructionAdapter iv = new InstructionAdapter(mv);

                iv.load(0, JetTypeMapper.TYPE_OBJECT);
                for (int i = 1, reg = 1; i < argTypes.length; i++) {
                    Type argType = argTypes[i];
                    iv.load(reg, argType);
                    //noinspection AssignmentToForLoopParameter
                    reg += argType.getSize();
                }
                iv.invokespecial(owner, originalMethod.getName(), originalMethod.getDescriptor());

                iv.areturn(method.getReturnType());
                FunctionCodegen.endVisit(iv, "accessor", null);
            }
        }
        else if (entry.getValue() instanceof PropertyDescriptor) {
            PropertyDescriptor bridge = (PropertyDescriptor) entry.getValue();
            PropertyDescriptor original = (PropertyDescriptor) entry.getKey();

            {
                Method method = typeMapper.mapGetterSignature(bridge, OwnerKind.IMPLEMENTATION).getJvmMethodSignature().getAsmMethod();
                JvmPropertyAccessorSignature originalSignature = typeMapper.mapGetterSignature(original, OwnerKind.IMPLEMENTATION);
                Method originalMethod = originalSignature.getJvmMethodSignature().getAsmMethod();
                MethodVisitor mv =
                        v.newMethod(null, ACC_BRIDGE | ACC_SYNTHETIC | ACC_STATIC, method.getName(), method.getDescriptor(), null, null);
                PropertyGetterDescriptor getter = ((PropertyDescriptor) entry.getValue()).getGetter();
                assert getter != null;
                PropertyCodegen.generateJetPropertyAnnotation(mv, originalSignature.getPropertyTypeKotlinSignature(),
                                                              originalSignature.getJvmMethodSignature().getKotlinTypeParameter(),
                                                              original,
                                                              getter.getVisibility());
                if (state.getClassBuilderMode() == ClassBuilderMode.STUBS) {
                    StubCodegen.generateStubCode(mv);
                }
                else if (state.getClassBuilderMode() == ClassBuilderMode.FULL) {
                    mv.visitCode();

                    InstructionAdapter iv = new InstructionAdapter(mv);

                    iv.load(0, JetTypeMapper.TYPE_OBJECT);
                    if (original.getVisibility() == Visibilities.PRIVATE) {
                        iv.getfield(typeMapper.getOwner(original, OwnerKind.IMPLEMENTATION).getInternalName(), original.getName().getName(),
                                    originalMethod.getReturnType().getDescriptor());
                    }
                    else {
                        iv.invokespecial(typeMapper.getOwner(original, OwnerKind.IMPLEMENTATION).getInternalName(),
                                         originalMethod.getName(), originalMethod.getDescriptor());
                    }

                    iv.areturn(method.getReturnType());
                    FunctionCodegen.endVisit(iv, "accessor", null);
                }
            }

            if (bridge.isVar()) {
                Method method = typeMapper.mapSetterSignature(bridge, OwnerKind.IMPLEMENTATION).getJvmMethodSignature().getAsmMethod();
                JvmPropertyAccessorSignature originalSignature2 = typeMapper.mapSetterSignature(original, OwnerKind.IMPLEMENTATION);
                Method originalMethod = originalSignature2.getJvmMethodSignature().getAsmMethod();
                MethodVisitor mv =
                        v.newMethod(null, ACC_STATIC | ACC_BRIDGE | ACC_FINAL, method.getName(), method.getDescriptor(), null, null);
                PropertySetterDescriptor setter = ((PropertyDescriptor) entry.getValue()).getSetter();
                assert setter != null;
                PropertyCodegen.generateJetPropertyAnnotation(mv, originalSignature2.getPropertyTypeKotlinSignature(),
                                                              originalSignature2.getJvmMethodSignature().getKotlinTypeParameter(),
                                                              original,
                                                              setter.getVisibility());
                if (state.getClassBuilderMode() == ClassBuilderMode.STUBS) {
                    StubCodegen.generateStubCode(mv);
                }
                else if (state.getClassBuilderMode() == ClassBuilderMode.FULL) {
                    mv.visitCode();

                    InstructionAdapter iv = new InstructionAdapter(mv);

                    iv.load(0, JetTypeMapper.TYPE_OBJECT);
                    Type[] argTypes = method.getArgumentTypes();
                    for (int i = 1, reg = 1; i < argTypes.length; i++) {
                        Type argType = argTypes[i];
                        iv.load(reg, argType);
                        //noinspection AssignmentToForLoopParameter
                        reg += argType.getSize();
                    }
                    if (original.getVisibility() == Visibilities.PRIVATE && original.getModality() == Modality.FINAL) {
                        iv.putfield(typeMapper.getOwner(original, OwnerKind.IMPLEMENTATION).getInternalName(), original.getName().getName(),
                                    originalMethod.getArgumentTypes()[0].getDescriptor());
                    }
                    else {
                        iv.invokespecial(typeMapper.getOwner(original, OwnerKind.IMPLEMENTATION).getInternalName(),
                                         originalMethod.getName(), originalMethod.getDescriptor());
                    }

                    iv.areturn(method.getReturnType());
                    FunctionCodegen.endVisit(iv, "accessor", null);
                }
            }
        }
        else {
            throw new UnsupportedOperationException();
        }
    }

    private void generateFieldForObjectInstance() {
        if (CodegenUtil.isNonLiteralObject(myClass)) {
            Type type = typeMapper.mapType(descriptor.getDefaultType(), MapTypeMode.VALUE);
            v.newField(myClass, ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "$instance", type.getDescriptor(), null, null);

            staticInitializerChunks.add(new CodeChunk() {
                @Override
                public void generate(InstructionAdapter v) {
                    String name = jvmName();
                    v.anew(Type.getObjectType(name));
                    v.dup();
                    v.invokespecial(name, "<init>", "()V");
                    v.putstatic(name, "$instance", typeMapper.mapType(descriptor.getDefaultType(), MapTypeMode.VALUE).getDescriptor());
                }
            });
        }
    }

    private void generateFieldForClassObject() {
        final JetClassObject classObject = getClassObject();
        if (classObject != null) {
            final ClassDescriptor descriptor1 = bindingContext.get(BindingContext.CLASS, classObject.getObjectDeclaration());
            assert descriptor1 != null;
            Type type = Type.getObjectType(typeMapper.mapType(descriptor1.getDefaultType(), MapTypeMode.VALUE).getInternalName());
            v.newField(classObject, ACC_PUBLIC | ACC_STATIC, "$classobj", type.getDescriptor(), null, null);

            staticInitializerChunks.add(new CodeChunk() {
                @Override
                public void generate(InstructionAdapter v) {
                    final ClassDescriptor descriptor1 = bindingContext.get(BindingContext.CLASS, classObject.getObjectDeclaration());
                    assert descriptor1 != null;
                    String name = typeMapper.mapType(descriptor1.getDefaultType(), MapTypeMode.IMPL).getInternalName();
                    final Type classObjectType = Type.getObjectType(name);
                    v.anew(classObjectType);
                    v.dup();
                    v.invokespecial(name, "<init>", "()V");
                    v.putstatic(typeMapper.mapType(descriptor.getDefaultType(), MapTypeMode.VALUE).getInternalName(), "$classobj",
                                classObjectType.getDescriptor());
                }
            });
        }
    }

    protected void generatePrimaryConstructor() {
        if (ignoreIfTraitOrAnnotation()) return;

        if (kind != OwnerKind.IMPLEMENTATION) {
            throw new IllegalStateException("incorrect kind for primary constructor: " + kind);
        }

        ConstructorDescriptor constructorDescriptor = bindingContext.get(BindingContext.CONSTRUCTOR, myClass);

        CodegenContexts.ConstructorContext constructorContext = context.intoConstructor(constructorDescriptor, typeMapper);

        JvmMethodSignature constructorMethod;
        CallableMethod callableMethod;
        boolean hasThis0 = typeMapper.hasThis0(descriptor);
        if (constructorDescriptor == null) {
            BothSignatureWriter signatureWriter = new BothSignatureWriter(BothSignatureWriter.Mode.METHOD, false);

            signatureWriter.writeFormalTypeParametersStart();
            signatureWriter.writeFormalTypeParametersEnd();

            signatureWriter.writeParametersStart();

            if (hasThis0) {
                signatureWriter.writeParameterType(JvmMethodParameterKind.THIS0);
                typeMapper
                        .mapType(typeMapper.getClosureAnnotator().getEclosingClassDescriptor(descriptor).getDefaultType(), signatureWriter,
                                 MapTypeMode.VALUE);
                signatureWriter.writeParameterTypeEnd();
            }

            signatureWriter.writeParametersEnd();

            signatureWriter.writeVoidReturn();

            constructorMethod = signatureWriter.makeJvmMethodSignature("<init>");
            callableMethod =
                    new CallableMethod(JvmClassName.byInternalName("Ignored"), null, null, constructorMethod, INVOKESPECIAL, null, null,
                                       null);
        }
        else {
            callableMethod = typeMapper.mapToCallableMethod(constructorDescriptor,
                                                            typeMapper.hasThis0(constructorDescriptor.getContainingDeclaration()));
            constructorMethod = callableMethod.getSignature();
        }

        lookupDelegateExpressionInClosureIfPresent();

        ObjectOrClosureCodegen closure = context.closure;
        int firstSuperArgument = -1;
        final List<JvmMethodParameterSignature> consArgTypes =
                new LinkedList<JvmMethodParameterSignature>(constructorMethod.getKotlinParameterTypes());

        int insert = addClosureToConstructorParameters(hasThis0, closure, consArgTypes);

        if (myClass instanceof JetObjectDeclaration && ((JetObjectDeclaration) myClass).isObjectLiteral()) {
            if (superCall instanceof JetDelegatorToSuperCall) {
                if (closure != null) {
                    closure.superCall = (JetDelegatorToSuperCall) superCall;
                }
                DeclarationDescriptor declarationDescriptor = bindingContext.get(BindingContext.REFERENCE_TARGET,
                                                                                 ((JetDelegatorToSuperCall) superCall).getCalleeExpression()
                                                                                         .getConstructorReferenceExpression());
                if (declarationDescriptor instanceof ClassDescriptorFromSource) {
                    declarationDescriptor = ((ClassDescriptorFromSource) declarationDescriptor).getUnsubstitutedPrimaryConstructor();
                }
                ConstructorDescriptor superConstructor = (ConstructorDescriptor) declarationDescriptor;
                CallableMethod superCallable = typeMapper.mapToCallableMethod(superConstructor, typeMapper
                        .hasThis0(superConstructor.getContainingDeclaration()));
                firstSuperArgument = insert;
                for (Type t : superCallable.getSignature().getAsmMethod().getArgumentTypes()) {
                    consArgTypes.add(insert++, new JvmMethodParameterSignature(t, "", JvmMethodParameterKind.SHARED_VAR));
                }
            }

            constructorMethod = JvmMethodSignature.simple("<init>", Type.VOID_TYPE, consArgTypes);
        }

        assert constructorDescriptor != null;
        int flags = JetTypeMapper.getAccessModifiers(constructorDescriptor, 0);
        final MethodVisitor mv = v.newMethod(myClass, flags, constructorMethod.getName(), constructorMethod.getAsmMethod().getDescriptor(),
                                             constructorMethod.getGenericsSignature(), null);
        if (state.getClassBuilderMode() == ClassBuilderMode.SIGNATURES) return;

        AnnotationVisitor jetConstructorVisitor = mv.visitAnnotation(JvmStdlibNames.JET_CONSTRUCTOR.getDescriptor(), true);

        int flagsValue = BitSetUtils.toInt(CodegenUtil.getFlagsForVisibility(constructorDescriptor.getVisibility()));
        if (JvmStdlibNames.FLAGS_DEFAULT_VALUE != flagsValue) {
            jetConstructorVisitor.visit(JvmStdlibNames.JET_CLASS_FLAGS_FIELD, flagsValue);
        }

        jetConstructorVisitor.visitEnd();

        AnnotationCodegen.forMethod(mv, typeMapper).genAnnotations(constructorDescriptor);

        writeParameterAnnotations(constructorDescriptor, constructorMethod, hasThis0, mv);

        if (state.getClassBuilderMode() == ClassBuilderMode.STUBS) {
            StubCodegen.generateStubCode(mv);
            return;
        }

        generatePrimiryConstructorImpl(constructorDescriptor, constructorContext, constructorMethod, callableMethod, hasThis0, closure,
                                       firstSuperArgument, mv);
    }

    private void generatePrimiryConstructorImpl(
            ConstructorDescriptor constructorDescriptor,
            CodegenContexts.ConstructorContext constructorContext,
            JvmMethodSignature constructorMethod,
            CallableMethod callableMethod,
            boolean hasThis0,
            ObjectOrClosureCodegen closure,
            int firstSuperArgument,
            MethodVisitor mv
    ) {
        mv.visitCode();

        List<ValueParameterDescriptor> paramDescrs = constructorDescriptor != null
                                                     ? constructorDescriptor.getValueParameters()
                                                     : Collections.<ValueParameterDescriptor>emptyList();

        ConstructorFrameMap frameMap = new ConstructorFrameMap(callableMethod, constructorDescriptor, hasThis0);

        final InstructionAdapter iv = new InstructionAdapter(mv);
        ExpressionCodegen codegen = new ExpressionCodegen(mv, frameMap, Type.VOID_TYPE, constructorContext, state);

        Type classType = typeMapper.mapType(descriptor.getDefaultType(), MapTypeMode.IMPL);
        JvmClassName classname = JvmClassName.byType(classType);

        if (superCall == null) {
            genSimpleSuperCall(iv);
        }
        else if (superCall instanceof JetDelegatorToSuperClass) {
            genSuperCallToDelegatorToSuperClass(iv);
        }
        else {
            ConstructorDescriptor constructorDescriptor1 = (ConstructorDescriptor) bindingContext.get(BindingContext.REFERENCE_TARGET,
                                                                                                      ((JetDelegatorToSuperCall) superCall)
                                                                                                              .getCalleeExpression()
                                                                                                              .getConstructorReferenceExpression());
            generateDelegatorToConstructorCall(iv, codegen, (JetDelegatorToSuperCall) superCall, constructorDescriptor1, frameMap,
                                               firstSuperArgument);
        }

        final ClassDescriptor outerDescriptor = typeMapper.getClosureAnnotator().getEclosingClassDescriptor(descriptor);
        final boolean hasOuterThis = typeMapper.hasThis0(descriptor) && outerDescriptor != null;
        if (hasOuterThis) {
            final Type type = typeMapper.mapType(outerDescriptor.getDefaultType(), MapTypeMode.VALUE);
            String interfaceDesc = type.getDescriptor();
            final String fieldName = "this$0";
            v.newField(myClass, ACC_FINAL, fieldName, interfaceDesc, null, null);
            iv.load(0, classType);
            iv.load(frameMap.getOuterThisIndex(), type);
            iv.putfield(classname.getInternalName(), fieldName, interfaceDesc);
        }

        if (closure != null) {
            int k = hasOuterThis ? 2 : 1;
            if (closure.captureReceiver != null) {
                iv.load(0, JetTypeMapper.TYPE_OBJECT);
                final Type asmType = typeMapper.mapType(closure.captureReceiver.getDefaultType(), MapTypeMode.IMPL);
                iv.load(1, asmType);
                iv.putfield(typeMapper.mapType(descriptor.getDefaultType(), MapTypeMode.VALUE).getInternalName(), "receiver$0",
                            asmType.getDescriptor());
                k += asmType.getSize();
            }

            for (DeclarationDescriptor varDescr : closure.closure.keySet()) {
                if (varDescr instanceof VariableDescriptor && !(varDescr instanceof PropertyDescriptor)) {
                    Type sharedVarType = typeMapper.getSharedVarType(varDescr);
                    if (sharedVarType == null) {
                        sharedVarType = typeMapper.mapType(((VariableDescriptor) varDescr).getType(), MapTypeMode.VALUE);
                    }
                    iv.load(0, JetTypeMapper.TYPE_OBJECT);
                    iv.load(k, StackValue.refType(sharedVarType));
                    k += StackValue.refType(sharedVarType).getSize();
                    iv.putfield(typeMapper.mapType(descriptor.getDefaultType(), MapTypeMode.VALUE).getInternalName(),
                                "$" + varDescr.getName(), sharedVarType.getDescriptor());
                }
            }
        }

        int n = 0;
        for (JetDelegationSpecifier specifier : myClass.getDelegationSpecifiers()) {
            if (specifier == superCall) {
                continue;
            }

            if (specifier instanceof JetDelegatorByExpressionSpecifier) {
                genCallToDelegatorByExpressionSpecifier(iv, codegen, classType, classname, n++, specifier);
            }
        }

        int curParam = 0;
        List<JetParameter> constructorParameters = getPrimaryConstructorParameters();
        for (JetParameter parameter : constructorParameters) {
            if (parameter.getValOrVarNode() != null) {
                VariableDescriptor descriptor = paramDescrs.get(curParam);
                Type type = typeMapper.mapType(descriptor.getType(), MapTypeMode.VALUE);
                iv.load(0, classType);
                iv.load(frameMap.getIndex(descriptor), type);
                iv.putfield(classname.getInternalName(), descriptor.getName().getName(), type.getDescriptor());
            }
            curParam++;
        }

        generateInitializers(codegen, iv, myClass.getDeclarations(), bindingContext, typeMapper);

        mv.visitInsn(RETURN);
        FunctionCodegen.endVisit(mv, "constructor", myClass);

        assert constructorDescriptor != null;
        FunctionCodegen.generateDefaultIfNeeded(constructorContext, state, v, constructorMethod.getAsmMethod(), constructorDescriptor,
                                                OwnerKind.IMPLEMENTATION);
    }

    private void genSuperCallToDelegatorToSuperClass(InstructionAdapter iv) {
        iv.load(0, Type.getType("L" + superClass + ";"));
        JetType superType = bindingContext.get(BindingContext.TYPE, superCall.getTypeReference());
        List<Type> parameterTypes = new ArrayList<Type>();
        assert superType != null;
        ClassDescriptor superClassDescriptor = (ClassDescriptor) superType.getConstructor().getDeclarationDescriptor();
        if (typeMapper.hasThis0(superClassDescriptor)) {
            iv.load(1, JetTypeMapper.TYPE_OBJECT);
            parameterTypes.add(typeMapper.mapType(
                    typeMapper.getClosureAnnotator().getEclosingClassDescriptor(descriptor).getDefaultType(), MapTypeMode.VALUE));
        }
        Method superCallMethod = new Method("<init>", Type.VOID_TYPE, parameterTypes.toArray(new Type[parameterTypes.size()]));
        //noinspection ConstantConditions
        iv.invokespecial(typeMapper.mapType(superClassDescriptor.getDefaultType(), MapTypeMode.VALUE).getInternalName(), "<init>",
                         superCallMethod.getDescriptor());
    }

    private void genSimpleSuperCall(InstructionAdapter iv) {
        iv.load(0, Type.getType("L" + superClass + ";"));
        if (descriptor.getKind() == ClassKind.ENUM_CLASS || descriptor.getKind() == ClassKind.ENUM_ENTRY) {
            iv.load(1, JetTypeMapper.JL_STRING_TYPE);
            iv.load(2, Type.INT_TYPE);
            iv.invokespecial(superClass, "<init>", "(Ljava/lang/String;I)V");
        }
        else {
            iv.invokespecial(superClass, "<init>", "()V");
        }
    }

    private void writeParameterAnnotations(
            ConstructorDescriptor constructorDescriptor,
            JvmMethodSignature constructorMethod,
            boolean hasThis0,
            MethodVisitor mv
    ) {
        if (constructorDescriptor != null) {
            int i = 0;

            if (hasThis0) {
                i++;
            }

            if (descriptor.getKind() == ClassKind.ENUM_CLASS || descriptor.getKind() == ClassKind.ENUM_ENTRY) {
                i += 2;
            }

            for (ValueParameterDescriptor valueParameter : constructorDescriptor.getValueParameters()) {
                AnnotationCodegen.forParameter(i, mv, state.getInjector().getJetTypeMapper()).genAnnotations(valueParameter);
                JetValueParameterAnnotationWriter jetValueParameterAnnotation =
                        JetValueParameterAnnotationWriter.visitParameterAnnotation(mv, i);
                jetValueParameterAnnotation.writeName(valueParameter.getName().getName());
                jetValueParameterAnnotation.writeHasDefaultValue(valueParameter.declaresDefaultValue());
                jetValueParameterAnnotation.writeType(constructorMethod.getKotlinParameterType(i));
                jetValueParameterAnnotation.visitEnd();
                ++i;
            }
        }
    }

    private void genCallToDelegatorByExpressionSpecifier(
            InstructionAdapter iv,
            ExpressionCodegen codegen,
            Type classType,
            JvmClassName classname,
            int n,
            JetDelegationSpecifier specifier
    ) {
        iv.load(0, classType);
        codegen.genToJVMStack(((JetDelegatorByExpressionSpecifier) specifier).getDelegateExpression());

        JetType superType = bindingContext.get(BindingContext.TYPE, specifier.getTypeReference());
        assert superType != null;
        ClassDescriptor superClassDescriptor = (ClassDescriptor) superType.getConstructor().getDeclarationDescriptor();
        assert superClassDescriptor != null;
        String delegateField = "$delegate_" + n;
        Type fieldType = typeMapper.mapType(superClassDescriptor.getDefaultType(), MapTypeMode.VALUE);
        String fieldDesc = fieldType.getDescriptor();
        v.newField(specifier, ACC_PRIVATE, delegateField, fieldDesc, /*TODO*/null, null);
        StackValue field = StackValue.field(fieldType, classname, delegateField, false);
        field.store(fieldType, iv);

        JetClass superClass = (JetClass) BindingContextUtils.classDescriptorToDeclaration(bindingContext, superClassDescriptor);
        final CodegenContext delegateContext = context.intoClass(superClassDescriptor,
                                                                 new OwnerKind.DelegateKind(StackValue.field(fieldType, classname,
                                                                                                             delegateField, false),
                                                                                            typeMapper.mapType(superClassDescriptor
                                                                                                                       .getDefaultType(),
                                                                                                               MapTypeMode.IMPL)
                                                                                                    .getInternalName()),
                                                                 state.getInjector().getJetTypeMapper());
        generateDelegates(superClass, delegateContext, field);
    }

    private int addClosureToConstructorParameters(
            boolean hasThis0,
            ObjectOrClosureCodegen closure,
            List<JvmMethodParameterSignature> consArgTypes
    ) {
        int insert = 0;
        if (closure != null) {
            if (closure.captureThis != null) {
                if (!hasThis0) {
                    consArgTypes.add(insert,
                                     new JvmMethodParameterSignature(Type.getObjectType(context.getThisDescriptor().getName().getName()),
                                                                     "", JvmMethodParameterKind.THIS0));
                }
                insert++;
            }
            else {
                if (hasThis0) {
                    insert++;
                }
            }

            if (closure.captureReceiver != null) {
                final Type asmType = typeMapper.mapType(closure.captureReceiver.getDefaultType(), MapTypeMode.IMPL);
                consArgTypes.add(insert++, new JvmMethodParameterSignature(asmType, "", JvmMethodParameterKind.RECEIVER));
            }

            for (DeclarationDescriptor descriptor : closure.closure.keySet()) {
                if (descriptor instanceof VariableDescriptor && !(descriptor instanceof PropertyDescriptor)) {
                    final Type sharedVarType = typeMapper.getSharedVarType(descriptor);
                    final Type type;
                    if (sharedVarType != null) {
                        type = sharedVarType;
                    }
                    else {
                        type = state.getInjector().getJetTypeMapper()
                                .mapType(((VariableDescriptor) descriptor).getType(), MapTypeMode.VALUE);
                    }
                    consArgTypes.add(insert++, new JvmMethodParameterSignature(type, "", JvmMethodParameterKind.SHARED_VAR));
                }
                else if (descriptor instanceof FunctionDescriptor) {
                    assert closure.captureReceiver != null;
                }
            }
        }
        return insert;
    }

    private void lookupDelegateExpressionInClosureIfPresent() {
        if (context.closure != null) {
            for (JetDelegationSpecifier specifier : myClass.getDelegationSpecifiers()) {
                if (specifier != superCall && specifier instanceof JetDelegatorByExpressionSpecifier) {
                    JetExpression delegateExpression = ((JetDelegatorByExpressionSpecifier) specifier).getDelegateExpression();
                    assert delegateExpression != null;
                    delegateExpression.accept(new JetVisitorVoid() {
                        @Override
                        public void visitJetElement(JetElement e) {
                            e.acceptChildren(this);
                        }

                        @Override
                        public void visitSimpleNameExpression(JetSimpleNameExpression expr) {
                            context.closure.lookupInContext(bindingContext.get(BindingContext.REFERENCE_TARGET, expr), null);
                        }
                    });
                }
            }
        }
    }

    private boolean ignoreIfTraitOrAnnotation() {
        if (myClass instanceof JetClass) {
            JetClass aClass = (JetClass) myClass;
            if (aClass.isTrait()) {
                return true;
            }
            if (aClass.isAnnotation()) {
                return true;
            }
        }
        return false;
    }

    private void generateTraitMethods() {
        if (myClass instanceof JetClass &&
            (((JetClass) myClass).isTrait() || myClass.hasModifier(JetTokens.ABSTRACT_KEYWORD))) {
            return;
        }

        for (Pair<CallableMemberDescriptor, CallableMemberDescriptor> needDelegates : getTraitImplementations(descriptor)) {
            if (needDelegates.second instanceof SimpleFunctionDescriptor) {
                generateDelegationToTraitImpl((FunctionDescriptor) needDelegates.second, (FunctionDescriptor) needDelegates.first);
            }
            else if (needDelegates.second instanceof PropertyDescriptor) {
                PropertyDescriptor property = (PropertyDescriptor) needDelegates.second;
                List<PropertyAccessorDescriptor> inheritedAccessors = ((PropertyDescriptor) needDelegates.first).getAccessors();
                for (PropertyAccessorDescriptor accessor : property.getAccessors()) {
                    for (PropertyAccessorDescriptor inheritedAccessor : inheritedAccessors) {
                        if (inheritedAccessor.getClass() == accessor.getClass()) { // same accessor kind
                            generateDelegationToTraitImpl(accessor, inheritedAccessor);
                        }
                    }
                }
            }
        }
    }


    private void generateDelegationToTraitImpl(FunctionDescriptor fun, @NotNull FunctionDescriptor inheritedFun) {
        DeclarationDescriptor containingDeclaration = fun.getContainingDeclaration();
        if (containingDeclaration instanceof ClassDescriptor) {
            ClassDescriptor declaration = (ClassDescriptor) containingDeclaration;
            if (declaration.getKind() == ClassKind.TRAIT) {
                int flags = ACC_PUBLIC; // TODO.

                Method function;
                Method functionOriginal;
                if (fun instanceof PropertyAccessorDescriptor) {
                    PropertyDescriptor property = ((PropertyAccessorDescriptor) fun).getCorrespondingProperty();
                    if (fun instanceof PropertyGetterDescriptor) {
                        function = typeMapper.mapGetterSignature(property, OwnerKind.IMPLEMENTATION).getJvmMethodSignature().getAsmMethod();
                        functionOriginal =
                                typeMapper.mapGetterSignature(property.getOriginal(), OwnerKind.IMPLEMENTATION).getJvmMethodSignature()
                                        .getAsmMethod();
                    }
                    else if (fun instanceof PropertySetterDescriptor) {
                        function = typeMapper.mapSetterSignature(property, OwnerKind.IMPLEMENTATION).getJvmMethodSignature().getAsmMethod();
                        functionOriginal =
                                typeMapper.mapSetterSignature(property.getOriginal(), OwnerKind.IMPLEMENTATION).getJvmMethodSignature()
                                        .getAsmMethod();
                    }
                    else {
                        throw new IllegalStateException("Accessor is neither getter, nor setter, what is it?");
                    }
                }
                else {
                    function = typeMapper.mapSignature(fun.getName(), fun).getAsmMethod();
                    functionOriginal = typeMapper.mapSignature(fun.getName(), fun.getOriginal()).getAsmMethod();
                }

                final MethodVisitor mv = v.newMethod(myClass, flags, function.getName(), function.getDescriptor(), null, null);
                AnnotationCodegen.forMethod(mv, state.getInjector().getJetTypeMapper()).genAnnotations(fun);

                JvmMethodSignature jvmSignature =
                        typeMapper.mapToCallableMethod(inheritedFun, false, OwnerKind.IMPLEMENTATION).getSignature();
                JetMethodAnnotationWriter aw = JetMethodAnnotationWriter.visitAnnotation(mv);
                BitSet kotlinFlags = CodegenUtil.getFlagsForVisibility(fun.getVisibility());
                if (fun instanceof PropertyAccessorDescriptor) {
                    kotlinFlags.set(JvmStdlibNames.FLAG_PROPERTY_BIT);
                    aw.writeTypeParameters(jvmSignature.getKotlinTypeParameter());
                    aw.writePropertyType(jvmSignature.getKotlinReturnType());
                }
                else {
                    JetType returnType = fun.getReturnType();
                    assert returnType != null;
                    aw.writeNullableReturnType(returnType.isNullable());
                    aw.writeTypeParameters(jvmSignature.getKotlinTypeParameter());
                    aw.writeReturnType(jvmSignature.getKotlinReturnType());
                }
                aw.writeFlags(kotlinFlags);
                aw.visitEnd();

                if (state.getClassBuilderMode() == ClassBuilderMode.STUBS) {
                    StubCodegen.generateStubCode(mv);
                }
                else if (state.getClassBuilderMode() == ClassBuilderMode.FULL) {
                    mv.visitCode();
                    FrameMap frameMap = context.prepareFrame(state.getInjector().getJetTypeMapper());
                    ExpressionCodegen codegen =
                            new ExpressionCodegen(mv, frameMap, jvmSignature.getAsmMethod().getReturnType(), context, state);
                    codegen.generateThisOrOuter(descriptor);    // ??? wouldn't it be addClosureToConstructorParameters good idea to put it?

                    Type[] argTypes = function.getArgumentTypes();
                    List<Type> originalArgTypes = jvmSignature.getValueParameterTypes();
                    InstructionAdapter iv = new InstructionAdapter(mv);
                    iv.load(0, JetTypeMapper.TYPE_OBJECT);
                    for (int i = 0, reg = 1; i < argTypes.length; i++) {
                        Type argType = argTypes[i];
                        iv.load(reg, argType);
                        StackValue.coerce(argType, originalArgTypes.get(i), iv);
                        //noinspection AssignmentToForLoopParameter
                        reg += argType.getSize();
                    }

                    JetType jetType = TraitImplBodyCodegen.getSuperClass(declaration);
                    Type type = typeMapper.mapType(jetType, MapTypeMode.IMPL);
                    if (type.getInternalName().equals("java/lang/Object")) {
                        jetType = declaration.getDefaultType();
                        type = typeMapper.mapType(jetType, MapTypeMode.IMPL);
                    }

                    String fdescriptor = functionOriginal.getDescriptor().replace("(", "(" + type.getDescriptor());
                    Type type1 =
                            typeMapper.mapType(((ClassDescriptor) fun.getContainingDeclaration()).getDefaultType(), MapTypeMode.TRAIT_IMPL);
                    iv.invokestatic(type1.getInternalName(), function.getName(), fdescriptor);
                    if (function.getReturnType().getSort() == Type.OBJECT &&
                        !function.getReturnType().equals(functionOriginal.getReturnType())) {
                        iv.checkcast(function.getReturnType());
                    }
                    iv.areturn(function.getReturnType());
                    FunctionCodegen.endVisit(iv, "trait method", BindingContextUtils.callableDescriptorToDeclaration(bindingContext, fun));
                }

                FunctionCodegen.generateBridgeIfNeeded(context, state, v, function, fun, kind);
            }
        }
    }

    private void generateDelegatorToConstructorCall(
            InstructionAdapter iv, ExpressionCodegen codegen, JetCallElement constructorCall,
            ConstructorDescriptor constructorDescriptor,
            ConstructorFrameMap frameMap, int firstSuperArgument
    ) {
        ClassDescriptor classDecl = constructorDescriptor.getContainingDeclaration();

        iv.load(0, TYPE_OBJECT);
        if (classDecl.getKind() == ClassKind.ENUM_CLASS || classDecl.getKind() == ClassKind.ENUM_ENTRY) {
            iv.load(1, JetTypeMapper.TYPE_OBJECT);
            iv.load(2, Type.INT_TYPE);
        }

        if (classDecl.getContainingDeclaration() instanceof ClassDescriptor) {
            iv.load(frameMap.getOuterThisIndex(),
                    typeMapper.mapType(((ClassDescriptor) descriptor.getContainingDeclaration()).getDefaultType(), MapTypeMode.IMPL));
        }

        CallableMethod method = typeMapper
                .mapToCallableMethod(constructorDescriptor, typeMapper.hasThis0(constructorDescriptor.getContainingDeclaration()));

        if (myClass instanceof JetObjectDeclaration &&
            superCall instanceof JetDelegatorToSuperCall &&
            ((JetObjectDeclaration) myClass).isObjectLiteral()) {
            ConstructorDescriptor superConstructor = (ConstructorDescriptor) bindingContext.get(BindingContext.REFERENCE_TARGET,
                                                                                                ((JetDelegatorToSuperCall) superCall)
                                                                                                        .getCalleeExpression()
                                                                                                        .getConstructorReferenceExpression());
            assert superConstructor != null;
            CallableMethod superCallable = typeMapper.mapToCallableMethod(superConstructor,
                                                                          typeMapper.hasThis0(superConstructor.getContainingDeclaration()));
            int nextVar = firstSuperArgument + 1;
            for (Type t : superCallable.getSignature().getAsmMethod().getArgumentTypes()) {
                iv.load(nextVar, t);
                nextVar += t.getSize();
            }
            method.invoke(codegen.v);
        }
        else {
            codegen.invokeMethodWithArguments(method, constructorCall, StackValue.none());
        }
    }

    @Override
    protected void generateDeclaration(PropertyCodegen propertyCodegen, JetDeclaration declaration, FunctionCodegen functionCodegen) {
        if (declaration instanceof JetClassObject) {
            // done earlier in order to have accessors
        }
        else if (declaration instanceof JetEnumEntry && !((JetEnumEntry) declaration).hasPrimaryConstructor()) {
            String name = declaration.getName();
            final String desc = "L" + typeMapper.mapType(descriptor.getDefaultType(), MapTypeMode.IMPL).getInternalName() + ";";
            v.newField(declaration, ACC_PUBLIC | ACC_ENUM | ACC_STATIC | ACC_FINAL, name, desc, null, null);
            if (myEnumConstants.isEmpty()) {
                staticInitializerChunks.add(new CodeChunk() {
                    @Override
                    public void generate(InstructionAdapter v) {
                        initializeEnumConstants(v);
                    }
                });
            }
            myEnumConstants.add((JetEnumEntry) declaration);
        }
        else {
            super.generateDeclaration(propertyCodegen, declaration, functionCodegen);
        }
    }

    private final List<JetEnumEntry> myEnumConstants = new ArrayList<JetEnumEntry>();

    private void initializeEnumConstants(InstructionAdapter iv) {
        ExpressionCodegen codegen = new ExpressionCodegen(iv, new FrameMap(), Type.VOID_TYPE, context, state);
        int ordinal = -1;
        JetType myType = descriptor.getDefaultType();
        Type myAsmType = typeMapper.mapType(myType, MapTypeMode.IMPL);

        assert myEnumConstants.size() > 0;
        JetType arrayType = JetStandardLibrary.getInstance().getArrayType(myType);
        Type arrayAsmType = typeMapper.mapType(arrayType, MapTypeMode.IMPL);
        v.newField(myClass, ACC_PRIVATE | ACC_STATIC | ACC_FINAL | ACC_SYNTHETIC, "$VALUES", arrayAsmType.getDescriptor(), null, null);

        iv.iconst(myEnumConstants.size());
        iv.newarray(myAsmType);
        iv.dup();

        for (JetEnumEntry enumConstant : myEnumConstants) {
            ordinal++;

            iv.dup();
            iv.iconst(ordinal);

            ClassDescriptor classDescriptor = bindingContext.get(BindingContext.CLASS, enumConstant);
            assert classDescriptor != null;
            String implClass = typeMapper.mapType(classDescriptor.getDefaultType(), MapTypeMode.IMPL).getInternalName();

            final List<JetDelegationSpecifier> delegationSpecifiers = enumConstant.getDelegationSpecifiers();
            if (delegationSpecifiers.size() > 1) {
                throw new UnsupportedOperationException("multiple delegation specifiers for enum constant not supported");
            }

            iv.anew(Type.getObjectType(implClass));
            iv.dup();

            iv.aconst(enumConstant.getName());
            iv.iconst(ordinal);

            if (delegationSpecifiers.size() == 1 && !state.getInjector().getClosureAnnotator().enumEntryNeedSubclass(enumConstant)) {
                final JetDelegationSpecifier specifier = delegationSpecifiers.get(0);
                if (specifier instanceof JetDelegatorToSuperCall) {
                    final JetDelegatorToSuperCall superCall = (JetDelegatorToSuperCall) specifier;
                    ConstructorDescriptor constructorDescriptor = (ConstructorDescriptor) bindingContext
                            .get(BindingContext.REFERENCE_TARGET, superCall.getCalleeExpression().getConstructorReferenceExpression());
                    assert constructorDescriptor != null;
                    CallableMethod method = typeMapper.mapToCallableMethod(constructorDescriptor, typeMapper
                            .hasThis0(constructorDescriptor.getContainingDeclaration()));
                    codegen.invokeMethodWithArguments(method, superCall, StackValue.none());
                }
                else {
                    throw new UnsupportedOperationException("unsupported type of enum constant initializer: " + specifier);
                }
            }
            else {
                iv.invokespecial(implClass, "<init>", "(Ljava/lang/String;I)V");
            }
            iv.dup();
            iv.putstatic(myAsmType.getInternalName(), enumConstant.getName(), "L" + myAsmType.getInternalName() + ";");
            iv.astore(TYPE_OBJECT);
        }
        iv.putstatic(myAsmType.getInternalName(), "$VALUES", arrayAsmType.getDescriptor());
    }

    public static void generateInitializers(
            @NotNull ExpressionCodegen codegen, @NotNull InstructionAdapter iv, @NotNull List<JetDeclaration> declarations,
            @NotNull BindingContext bindingContext, @NotNull JetTypeMapper typeMapper
    ) {
        for (JetDeclaration declaration : declarations) {
            if (declaration instanceof JetProperty) {
                final PropertyDescriptor propertyDescriptor = (PropertyDescriptor) bindingContext.get(BindingContext.VARIABLE, declaration);
                assert propertyDescriptor != null;
                if (Boolean.TRUE.equals(bindingContext.get(BindingContext.BACKING_FIELD_REQUIRED, propertyDescriptor))) {
                    final JetExpression initializer = ((JetProperty) declaration).getInitializer();
                    if (initializer != null) {
                        CompileTimeConstant<?> compileTimeValue = bindingContext.get(BindingContext.COMPILE_TIME_VALUE, initializer);
                        final JetType jetType = propertyDescriptor.getType();
                        if (compileTimeValue != null) {
                            Object value = compileTimeValue.getValue();
                            Type type = typeMapper.mapType(jetType, MapTypeMode.VALUE);
                            if (skipDefaultValue(propertyDescriptor, value, type)) continue;
                        }
                        iv.load(0, JetTypeMapper.TYPE_OBJECT);
                        Type type = codegen.expressionType(initializer);
                        if (jetType.isNullable()) {
                            type = JetTypeMapper.boxType(type);
                        }
                        codegen.gen(initializer, type);
                        // @todo write directly to the field. Fix test excloset.jet::test6
                        JvmClassName owner = typeMapper.getOwner(propertyDescriptor, OwnerKind.IMPLEMENTATION);
                        Type propType = typeMapper.mapType(jetType, MapTypeMode.VALUE);
                        StackValue.property(propertyDescriptor.getName().getName(), owner, owner,
                                            propType, false, false, false, null, null, 0).store(propType, iv);
                    }
                }
            }
            else if (declaration instanceof JetClassInitializer) {
                codegen.gen(((JetClassInitializer) declaration).getBody(), Type.VOID_TYPE);
            }
        }
    }

    private static boolean skipDefaultValue(PropertyDescriptor propertyDescriptor, Object value, Type type) {
        if (JetTypeMapper.isPrimitive(type)) {
            if (!propertyDescriptor.getType().isNullable() && value instanceof Number) {
                if (type == Type.INT_TYPE && ((Number) value).intValue() == 0) {
                    return true;
                }
                if (type == Type.BYTE_TYPE && ((Number) value).byteValue() == 0) {
                    return true;
                }
                if (type == Type.LONG_TYPE && ((Number) value).longValue() == 0L) {
                    return true;
                }
                if (type == Type.SHORT_TYPE && ((Number) value).shortValue() == 0) {
                    return true;
                }
                if (type == Type.DOUBLE_TYPE && ((Number) value).doubleValue() == 0d) {
                    return true;
                }
                if (type == Type.FLOAT_TYPE && ((Number) value).byteValue() == 0f) {
                    return true;
                }
            }
            if (type == Type.BOOLEAN_TYPE && value instanceof Boolean && !((Boolean) value)) {
                return true;
            }
            if (type == Type.CHAR_TYPE && value instanceof Character && ((Character) value) == 0) {
                return true;
            }
        }
        else {
            if (value == null) {
                return true;
            }
        }
        return false;
    }

    protected void generateDelegates(JetClass toClass, CodegenContext delegateContext, StackValue field) {
        final FunctionCodegen functionCodegen = new FunctionCodegen(delegateContext, v, state);
        final PropertyCodegen propertyCodegen = new PropertyCodegen(delegateContext, v, functionCodegen, state);

        ClassDescriptor classDescriptor = bindingContext.get(BindingContext.CLASS, toClass);
        for (DeclarationDescriptor declaration : descriptor.getDefaultType().getMemberScope().getAllDescriptors()) {
            if (declaration instanceof CallableMemberDescriptor) {
                CallableMemberDescriptor callableMemberDescriptor = (CallableMemberDescriptor) declaration;
                if (callableMemberDescriptor.getKind() == CallableMemberDescriptor.Kind.DELEGATION) {
                    Set<? extends CallableMemberDescriptor> overriddenDescriptors = callableMemberDescriptor.getOverriddenDescriptors();
                    for (CallableMemberDescriptor overriddenDescriptor : overriddenDescriptors) {
                        if (overriddenDescriptor.getContainingDeclaration() == classDescriptor) {
                            if (declaration instanceof PropertyDescriptor) {
                                propertyCodegen
                                        .genDelegate((PropertyDescriptor) declaration, (PropertyDescriptor) overriddenDescriptor, field);
                            }
                            else if (declaration instanceof SimpleFunctionDescriptor) {
                                functionCodegen.genDelegate((SimpleFunctionDescriptor) declaration, overriddenDescriptor, field);
                            }
                        }
                    }
                }
            }
        }
    }

    @Nullable
    private JetClassObject getClassObject() {
        return myClass instanceof JetClass ? ((JetClass) myClass).getClassObject() : null;
    }


    /**
     * Return pairs of descriptors. First is member of this that should be implemented by delegating to trait,
     * second is member of trait that contain implementation.
     */
    private static List<Pair<CallableMemberDescriptor, CallableMemberDescriptor>> getTraitImplementations(@NotNull ClassDescriptor classDescriptor) {
        List<Pair<CallableMemberDescriptor, CallableMemberDescriptor>> r = Lists.newArrayList();

        root:
        for (DeclarationDescriptor decl : classDescriptor.getDefaultType().getMemberScope().getAllDescriptors()) {
            if (!(decl instanceof CallableMemberDescriptor)) {
                continue;
            }

            CallableMemberDescriptor callableMemberDescriptor = (CallableMemberDescriptor) decl;
            if (callableMemberDescriptor.getKind() != CallableMemberDescriptor.Kind.FAKE_OVERRIDE) {
                continue;
            }

            Collection<CallableMemberDescriptor> overriddenDeclarations =
                    OverridingUtil.getOverriddenDeclarations(callableMemberDescriptor);
            for (CallableMemberDescriptor overriddenDeclaration : overriddenDeclarations) {
                if (overriddenDeclaration.getModality() != Modality.ABSTRACT) {
                    if (!CodegenUtil.isInterface(overriddenDeclaration.getContainingDeclaration())) {
                        continue root;
                    }
                }
            }

            for (CallableMemberDescriptor overriddenDeclaration : overriddenDeclarations) {
                if (overriddenDeclaration.getModality() != Modality.ABSTRACT) {
                    r.add(Pair.create(callableMemberDescriptor, overriddenDeclaration));
                }
            }
        }

        return r;
    }
}
