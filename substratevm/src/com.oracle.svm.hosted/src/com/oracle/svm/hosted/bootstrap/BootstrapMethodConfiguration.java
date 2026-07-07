/*
 * Copyright (c) 2023, 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.hosted.bootstrap;

import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.StringConcatFactory;
import java.lang.invoke.TypeDescriptor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.runtime.ObjectMethods;
import java.lang.runtime.SwitchBootstraps;
import java.util.HashSet;
import java.util.Set;

import org.graalvm.nativeimage.ImageSingletons;

import com.oracle.svm.core.feature.AutomaticallyRegisteredFeature;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.util.ReflectionUtil;

/**
 * Class storing a list of bootstrap methods that are allowed to be executed at build time. Those
 * methods are trusted methods from the JDK. Additionally used to register methods used by some
 * bootstrap methods for runtime reflection.
 */
@AutomaticallyRegisteredFeature
public class BootstrapMethodConfiguration implements InternalFeature {
    private final Set<Executable> buildTimeIndy = new HashSet<>();
    private final Set<Executable> buildTimeCondy = new HashSet<>();

    public static BootstrapMethodConfiguration singleton() {
        return ImageSingletons.lookup(BootstrapMethodConfiguration.class);
    }

    @Override
    public void duringSetup(DuringSetupAccess access) {
        /*
         * Bootstrap method used for Lambdas. Executing this method at run time implies defining
         * hidden class at run time, which is unsupported.
         */
        Method metafactory = ReflectionUtil.lookupMethod(LambdaMetafactory.class, "metafactory", MethodHandles.Lookup.class, String.class, MethodType.class,
                        MethodType.class, MethodHandle.class, MethodType.class);
        /* Alternate version of LambdaMetafactory.metafactory. */
        Method altMetafactory = ReflectionUtil.lookupMethod(LambdaMetafactory.class, "altMetafactory", MethodHandles.Lookup.class, String.class, MethodType.class,
                        Object[].class);
        buildTimeIndy.add(metafactory);
        buildTimeIndy.add(altMetafactory);

        /*
         * Bootstrap method used to optimize String concatenation. Executing it at run time
         * currently causes a StackOverFlow error as it infinitely calls itself.
         */
        Method makeConcat = ReflectionUtil.lookupMethod(StringConcatFactory.class, "makeConcat", MethodHandles.Lookup.class, String.class, MethodType.class);
        /* Alternate version of StringConcatFactory.makeConcat with constant arguments. */
        Method makeConcatWithConstants = ReflectionUtil.lookupMethod(StringConcatFactory.class, "makeConcatWithConstants", MethodHandles.Lookup.class, String.class,
                        MethodType.class, String.class, Object[].class);
        buildTimeIndy.add(makeConcat);
        buildTimeIndy.add(makeConcatWithConstants);

        /* Causes deadlock in Permission feature. */
        Method bootstrap = ReflectionUtil.lookupMethod(ObjectMethods.class, "bootstrap", MethodHandles.Lookup.class, String.class, TypeDescriptor.class, Class.class,
                        String.class, MethodHandle[].class);
        buildTimeIndy.add(bootstrap);

        /*
         * Bootstrap methods used for switch statements. Executing these methods at run time implies
         * defining hidden classes at run time, which is unsupported.
         */
        Method typeSwitch = ReflectionUtil.lookupMethod(SwitchBootstraps.class, "typeSwitch", MethodHandles.Lookup.class, String.class, MethodType.class,
                        Object[].class);
        Method enumSwitch = ReflectionUtil.lookupMethod(SwitchBootstraps.class, "enumSwitch", MethodHandles.Lookup.class, String.class, MethodType.class,
                        Object[].class);
        buildTimeIndy.add(typeSwitch);
        buildTimeIndy.add(enumSwitch);
    }

    public void addBuildTimeIndy(Executable method) {
        buildTimeIndy.add(method);
    }

    public void addBuildTimeCondy(Executable method) {
        buildTimeCondy.add(method);
    }

    /**
     * Check if the provided method is allowed to be executed at build time.
     */
    public boolean isIndyAllowedAtBuildTime(Executable method) {
        return method != null && buildTimeIndy.contains(method);
    }

    /**
     * Check if the provided method is allowed to be executed at build time.
     */
    public boolean isCondyAllowedAtBuildTime(Executable method) {
        return method != null && (buildTimeCondy.contains(method) || isProxyCondy(method));
    }

    /**
     * Every {@link Proxy} class has its own bootstrap method that is used for a constant dynamic.
     */
    private static boolean isProxyCondy(Executable method) {
        return Proxy.isProxyClass(method.getDeclaringClass()) && method.getName().equals("$getMethod");
    }
}
