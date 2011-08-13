/*
 * Copyright (c) 2008-2010, Hazel Ltd. All Rights Reserved.
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
 *
 */

package com.hazelcast.nio;

import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectStreamClass;
import java.lang.reflect.Constructor;
import java.util.logging.Level;

public abstract class AbstractSerializer {

    private static final ILogger logger = Logger.getLogger(AbstractSerializer.class.getName());

    private static final int OUTPUT_STREAM_BUFFER_SIZE = 100 << 10;

    private final FastByteArrayOutputStream bbos;
    private final FastByteArrayInputStream bbis;
    private final Serializer.DataSerializer ds;
    private final CustomSerializerAdapter cs;

    public AbstractSerializer(Serializer.DataSerializer ds, CustomSerializer cs) {
        this.ds = ds;
        this.cs = new CustomSerializerAdapter(cs);
        this.bbis = new FastByteArrayInputStream(new byte[10]);
        this.bbos = new FastByteArrayOutputStream(OUTPUT_STREAM_BUFFER_SIZE);
    }

    public static Object newInstance(final Class klass) throws Exception {
        final Constructor<?> constructor = klass.getDeclaredConstructor();
        if (!constructor.isAccessible()) {
            constructor.setAccessible(true);
        }
        return constructor.newInstance();
    }

    public static Class<?> loadClass(final String className) throws ClassNotFoundException {
        return loadClass(null, className);
    }

    public static Class<?> loadClass(final ClassLoader classLoader, final String className) throws ClassNotFoundException {
        if (className == null) {
            throw new IllegalArgumentException("ClassName cannot be null!");
        }
        if (className.startsWith("com.hazelcast")) {
            return AbstractSerializer.class.getClassLoader().loadClass(className);
        }
        ClassLoader theClassLoader = classLoader;
        if (theClassLoader == null) {
            theClassLoader = Thread.currentThread().getContextClassLoader();
        }
        if (className.startsWith("[L")) {
            // ClassLoader.loadClass doesn't understand Arrays!
            return Class.forName(className, true, theClassLoader);
        }
        if (theClassLoader != null) {
            return theClassLoader.loadClass(className);
        }
        return ClassLoader.getSystemClassLoader().loadClass(className);
    }

    public static ObjectInputStream newObjectInputStream(final InputStream in) throws IOException {
        return new ObjectInputStream(in) {
            @Override
            protected Class<?> resolveClass(final ObjectStreamClass desc) throws ClassNotFoundException {
                return loadClass(desc.getName());
            }
        };
    }

    protected void toByte(final FastByteArrayOutputStream bos, final Object object) {
        if (object == null) {
            return;
        }
        try {
            TypeSerializer ts = (ds.isSuitable(object)) ? ds : cs;
            bos.writeByte(ts.getTypeId());
            ts.write(bos, object);
            bos.flush();
        } catch (Throwable e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    protected Object toObject(final FastByteArrayInputStream bis) {
        try {
            final byte typeId = bis.readByte();
            TypeSerializer ts = (typeId == ds.getTypeId()) ? ds : cs;
            return ts.read(bis);
        } catch (Throwable e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    public byte[] toByteArray(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            this.bbos.reset();
            toByte(this.bbos, obj);
            final byte[] result = this.bbos.toByteArray();
            if (this.bbos.size() > OUTPUT_STREAM_BUFFER_SIZE) {
                this.bbos.set(new byte[OUTPUT_STREAM_BUFFER_SIZE]);
            }
            return result;
        } catch (Throwable e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    public Object toObject(byte[] byteArray) {
        if (byteArray == null || byteArray.length == 0) {
            return null;
        }
        this.bbis.set(byteArray, byteArray.length);
        return toObject(this.bbis);
    }
}