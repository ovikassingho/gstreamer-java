/*
 * Copyright (c) 2009 Andres Colubri
 * Copyright (c) 2007 Wayne Meissner
 * 
 * This file is part of gstreamer-java.
 *
 * This code is free software: you can redistribute it and/or modify it under 
 * the terms of the GNU Lesser General Public License version 3 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT 
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or 
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License 
 * version 3 for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * version 3 along with this work.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.gstreamer;

import static org.gstreamer.lowlevel.GObjectAPI.gobj;
import static org.gstreamer.lowlevel.GSignalAPI.gsignal;
import static org.gstreamer.lowlevel.GValueAPI.gvalue;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.gstreamer.lowlevel.EnumMapper;
import org.gstreamer.lowlevel.GObjectAPI;
import org.gstreamer.lowlevel.GSignalAPI;
import org.gstreamer.lowlevel.GType;
import org.gstreamer.lowlevel.IntPtr;
import org.gstreamer.lowlevel.NativeObject;
import org.gstreamer.lowlevel.RefCountedObject;
import org.gstreamer.lowlevel.GValueAPI.GValue;

import com.sun.jna.Callback;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import org.gstreamer.lowlevel.GlibAPI.GList;

/**
 * This is an abstract class providing some GObject-like facilities in a common 
 * base class.  Not intended for direct use.
 */
public abstract class GObject extends RefCountedObject {
    private static final Logger logger = Logger.getLogger(GObject.class.getName());
    private static final Level LIFECYCLE = Level.FINE;
    private static final Map<GObject, Boolean> strongReferences = new ConcurrentHashMap<GObject, Boolean>();
    private Map<Class<?>, Map<Object, GCallback>> callbackListeners;
    private Map<String, Map<Closure, ClosureProxy>> signalClosures;
    
    private final IntPtr objectID = new IntPtr(System.identityHashCode(this));

    public GObject(Initializer init) { 
        super(init.needRef ? initializer(init.ptr, false, init.ownsHandle) : init);
        logger.entering("GObject", "<init>", new Object[] { init });
        if (init.ownsHandle) {
            strongReferences.put(this, Boolean.TRUE);
            gobj.g_object_add_toggle_ref(init.ptr, toggle, objectID);
            if (!init.needRef) {
                unref();
            }
        }
    }
    
    /**
     * Sets the value of a <tt>GObject</tt> property.
     *
     * @param property The property to set.
     * @param data The value for the property.  This must be of the type expected
     * by gstreamer.
     */
    public void set(String property, Object data) {
        logger.entering("GObject", "set", new Object[] { property, data });
        GObjectAPI.GParamSpec propertySpec = findProperty(property);
        if (propertySpec == null) {
            throw new IllegalArgumentException("Unknown property: " + property);
        }
        final GType propType = propertySpec.value_type;
        
        GValue propValue = new GValue();
        gvalue.g_value_init(propValue, propType);
        if (propType.equals(GType.INT)) {
            gvalue.g_value_set_int(propValue, intValue(data));
        } else if (propType.equals(GType.UINT)) {
            gvalue.g_value_set_uint(propValue, intValue(data));
        } else if (propType.equals(GType.CHAR)) {
            gvalue.g_value_set_char(propValue, (byte) intValue(data));
        } else if (propType.equals(GType.UCHAR)) {
            gvalue.g_value_set_uchar(propValue, (byte) intValue(data));
        } else if (propType.equals(GType.LONG)) {
            gvalue.g_value_set_long(propValue, new NativeLong(longValue(data)));
        } else if (propType.equals(GType.ULONG)) {
            gvalue.g_value_set_ulong(propValue, new NativeLong(longValue(data)));
        } else if (propType.equals(GType.INT64)) {
            gvalue.g_value_set_int64(propValue, longValue(data));
        } else if (propType.equals(GType.UINT64)) {
            gvalue.g_value_set_uint64(propValue, longValue(data));
        } else if (propType.equals(GType.BOOLEAN)) {
            gvalue.g_value_set_boolean(propValue, booleanValue(data));
        } else if (propType.equals(GType.FLOAT)) {
            gvalue.g_value_set_float(propValue, floatValue(data));
        } else if (propType.equals(GType.DOUBLE)) {
            gvalue.g_value_set_double(propValue, doubleValue(data));
        } else if (propType.equals(GType.STRING)) {
            //
            // Special conversion of java URI to gstreamer compatible uri
            //
            if (data instanceof URI) {
                URI uri = (URI) data;
                String uriString = uri.toString();
                // Need to fixup file:/ to be file:/// for gstreamer
                if ("file".equals(uri.getScheme()) && uri.getHost() == null) {
                    final String path = uri.getRawPath();
                    uriString = "file://" + path;
                }
                gvalue.g_value_set_string(propValue, uriString);
            } else {
                gvalue.g_value_set_string(propValue, data.toString());
            }
        } else if (propType.equals(GType.OBJECT)) {
            gvalue.g_value_set_object(propValue, (GObject) data);
        } else if (gvalue.g_value_type_transformable(GType.INT64, propType)) {
            transform(data, GType.INT64, propValue);
        } else if (gvalue.g_value_type_transformable(GType.LONG, propType)) {
            transform(data, GType.LONG, propValue);
        } else if (gvalue.g_value_type_transformable(GType.INT, propType)) {
            transform(data, GType.INT, propValue);
        } else if (gvalue.g_value_type_transformable(GType.DOUBLE, propType)) {
            transform(data, GType.DOUBLE, propValue);
        } else if (gvalue.g_value_type_transformable(GType.FLOAT, propType)) {
            transform(data, GType.FLOAT, propValue);
        } else {
            // Old behaviour
            gobj.g_object_set(this, property, data);
            return;
        }
        gobj.g_object_set_property(this, property, propValue);
        gvalue.g_value_unset(propValue); // Release any memory
    }
    
    /**
     * Gets the current value of a <tt>GObject</tt> property.
     *
     * @param property The name of the property to get.
     *
     * @return A java value representing the <tt>GObject</tt> property value.
     */
    public Object get(String property) {
        logger.entering("GObject", "get", new Object[] { property });
        GObjectAPI.GParamSpec propertySpec = findProperty(property);
        if (propertySpec == null) {
            throw new IllegalArgumentException("Unknown property: " + property);
        }
        final GType propType = propertySpec.value_type;
        GValue propValue = new GValue();
        gvalue.g_value_init(propValue, propType);
        gobj.g_object_get_property(this, property, propValue);
        if (propType.equals(GType.INT)) {
            return gvalue.g_value_get_int(propValue);
        } else if (propType.equals(GType.UINT)) {
            return gvalue.g_value_get_uint(propValue);
        } else if (propType.equals(GType.CHAR)) {
            return Integer.valueOf(gvalue.g_value_get_char(propValue));
        } else if (propType.equals(GType.UCHAR)) {
            return Integer.valueOf(gvalue.g_value_get_uchar(propValue));
        } else if (propType.equals(GType.LONG)) {
            return gvalue.g_value_get_long(propValue).longValue();
        } else if (propType.equals(GType.ULONG)) {
            return gvalue.g_value_get_ulong(propValue).longValue();
        } else if (propType.equals(GType.INT64)) {
            return gvalue.g_value_get_int64(propValue);
        } else if (propType.equals(GType.UINT64)) {
            return gvalue.g_value_get_uint64(propValue);
        } else if (propType.equals(GType.BOOLEAN)) {
            return gvalue.g_value_get_boolean(propValue);
        } else if (propType.equals(GType.FLOAT)) {
            return gvalue.g_value_get_float(propValue);
        } else if (propType.equals(GType.DOUBLE)) {
            return gvalue.g_value_get_double(propValue);
        } else if (propType.equals(GType.STRING)) {
            return gvalue.g_value_get_string(propValue);
        } else if (propType.equals(GType.OBJECT)) {
            return gvalue.g_value_dup_object(propValue);
        } else if (gvalue.g_value_type_transformable(propType, GType.OBJECT)) {
            return gvalue.g_value_dup_object(transform(propValue, GType.OBJECT));
        } else if (gvalue.g_value_type_transformable(propType, GType.INT)) {
            return gvalue.g_value_get_int(transform(propValue, GType.INT));
        } else if (gvalue.g_value_type_transformable(propType, GType.INT64)) {
            return gvalue.g_value_get_int64(transform(propValue, GType.INT64));
        }
        else {
            throw new IllegalArgumentException("Unknown conversion from GType=" + propType);
        }
    }

    public Pointer getPointer(String property) {
        logger.entering("GObject", "getPointer", new Object[] { property });
        GObjectAPI.GParamSpec propertySpec = findProperty(property);
        if (propertySpec == null) {
            throw new IllegalArgumentException("Unknown property: " + property);
        }

        PointerByReference refPtr = new PointerByReference();
        gobj.g_object_get(this, property, refPtr, null);

        if (refPtr != null) {

            Pointer ptr = refPtr.getValue();

            if (ptr == null) {
                throw new IllegalArgumentException("Referenced value is NULL.");
            }

            return ptr;
        }
        else {
            throw new IllegalArgumentException("Got NULL pointer for property="+property);
        }
    }
    
    private static GValue transform(GValue src, GType dstType) {
        GValue dst = new GValue();
        gvalue.g_value_init(dst, dstType);
        gvalue.g_value_transform(src, dst);
        return dst;
    }
    private static void transform(Object data, GType type, GValue dst) {
        GValue src = new GValue();
        gvalue.g_value_init(src, type);
        setGValue(src, type, data);
        gvalue.g_value_transform(src, dst);
    }
    private static boolean setGValue(GValue value, GType type, Object data) {
        if (type.equals(GType.INT)) {
            gvalue.g_value_set_int(value, intValue(data));
        } else if (type.equals(GType.UINT)) {
            gvalue.g_value_set_uint(value, intValue(data));
        } else if (type.equals(GType.CHAR)) {
            gvalue.g_value_set_char(value, (byte) intValue(data));
        } else if (type.equals(GType.UCHAR)) {
            gvalue.g_value_set_uchar(value, (byte) intValue(data));
        } else if (type.equals(GType.LONG)) {
            gvalue.g_value_set_long(value, new NativeLong(longValue(data)));
        } else if (type.equals(GType.ULONG)) {
            gvalue.g_value_set_ulong(value, new NativeLong(longValue(data)));
        } else if (type.equals(GType.INT64)) {
            gvalue.g_value_set_int64(value, longValue(data));
        } else if (type.equals(GType.UINT64)) {
            gvalue.g_value_set_uint64(value, longValue(data));
        } else if (type.equals(GType.BOOLEAN)) {
            gvalue.g_value_set_boolean(value, booleanValue(data));
        } else if (type.equals(GType.FLOAT)) {
            gvalue.g_value_set_float(value, floatValue(data));
        } else if (type.equals(GType.DOUBLE)) {
            gvalue.g_value_set_double(value, doubleValue(data));
        } else {
            return false;
        }
        return true;
    }
    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        } else if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        } else if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        throw new IllegalArgumentException("Expected boolean value, not " + value.getClass());
    }
    private static int intValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        } else if (value instanceof String) {
            return Integer.parseInt((String) value);
        }
        throw new IllegalArgumentException("Expected integer value, not " + value.getClass());
    }
    private static long longValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        } else if (value instanceof String) {
            return Long.parseLong((String) value);
        }
        throw new IllegalArgumentException("Expected long value, not " + value.getClass());
    }
    private static float floatValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).floatValue();
        } else if (value instanceof String) {
            return Float.parseFloat((String) value);
        }
        throw new IllegalArgumentException("Expected float value, not " + value.getClass());
    }
    private static double doubleValue(Object value) {
        if (value instanceof Number) {
            return  ((Number) value).doubleValue();
        } else if (value instanceof String) {
            return Double.parseDouble((String) value);
        }
        throw new IllegalArgumentException("Expected double value, not " + value.getClass());
    }
    
    protected void disposeNativeHandle(Pointer ptr) {
        logger.log(LIFECYCLE, "Removing toggle ref " + getClass().getSimpleName() + " (" +  ptr + ")");
        gobj.g_object_remove_toggle_ref(ptr, toggle, objectID);
    }
    @Override
    protected void ref() {
        gobj.g_object_ref(this);
    }

    @Override
    protected void unref() {
        gobj.g_object_unref(this);
    }
    protected void invalidate() {
        try {
            // Need to increase the ref count before removing the toggle ref, so 
            // ensure the native object is not destroyed.
            if (ownsHandle.get()) {
                ref();

                // Disconnect the callback.
                gobj.g_object_remove_toggle_ref(handle(), toggle, objectID);
            }
            strongReferences.remove(this);
        } finally { 
            super.invalidate();
        }
    }
    
    protected NativeLong g_signal_connect(String signal, Callback callback) {
        logger.entering("GObject", "g_signal_connect", new Object[] { signal, callback });
        return gobj.g_signal_connect_data(this, signal, callback, null, null, 0);
    }

    private GList objectFor(Pointer ptr) {
        return GList.valueOf(ptr);
    }

    abstract protected class GCallback {
        protected final Callback cb;
        protected final NativeLong id;
        volatile boolean connected = false;

        protected GCallback(NativeLong id, Callback cb) {
            this.id = id != null ? id : new NativeLong(0);
            this.cb = cb;
            this.connected = this.id.intValue() != 0;
        }
        void remove() {
            if (connected) {
                disconnect();
                connected = false;
            }
        }
        abstract protected void disconnect();
        @Override
        protected final void finalize() {
            // Ensure the native callback is removed
            remove();
        }
    }
    private final class SignalCallback extends GCallback {
        protected SignalCallback(String signal, Callback cb) {
            super(g_signal_connect(signal, cb), cb);
            if (!connected) {
                throw new IllegalArgumentException(String.format("Failed to connect signal '%s'", signal));
            }
        }
        synchronized protected void disconnect() {
            gobj.g_signal_handler_disconnect(GObject.this, id);
        }
    }
    private synchronized final Map<Class<?>, Map<Object, GCallback>> getCallbackMap() {
        if (callbackListeners == null) {
            callbackListeners = new ConcurrentHashMap<Class<?>, Map<Object, GCallback>>();
        }
        return callbackListeners;
    }
    private synchronized final Map<String, Map<Closure, ClosureProxy>> getClosureMap() {
        if (signalClosures == null) {
            signalClosures = new ConcurrentHashMap<String, Map<Closure, ClosureProxy>>();
        }
        return signalClosures;
    }
    
    protected synchronized <T> void  addCallback(Class<T> listenerClass, T listener, GCallback cb) {
        final Map<Class<?>, Map<Object, GCallback>> signals = getCallbackMap();
        Map<Object, GCallback> m = signals.get(listenerClass);
        if (m == null) {
            m = new HashMap<Object, GCallback>();
            signals.put(listenerClass, m);
        }
        m.put(listener, cb);
    }
    
    public synchronized <T> void removeCallback(Class<T> listenerClass, T listener) {
        final Map<Class<?>, Map<Object, GCallback>> signals = getCallbackMap();
        Map<Object, GCallback> map = signals.get(listenerClass);
        if (map != null) {
            GCallback cb = map.remove(listener);
            if (cb != null) {
                cb.remove();
            }
            if (map.isEmpty()) {
                signals.remove(listenerClass);
                if (callbackListeners.isEmpty()) {
                    callbackListeners = null;
                }
            }
        }
    }
    public <T> void connect(Class<T> listenerClass, T listener, Callback cb) {
        String signal = listenerClass.getSimpleName().toLowerCase().replaceAll("_", "-");
        connect(signal, listenerClass, listener, cb);
    }
    
    public synchronized <T> void connect(String signal, Class<T> listenerClass, T listener, Callback cb) {
        addCallback(listenerClass, listener, new SignalCallback(signal, cb));
    }
    
    public synchronized <T> void disconnect(Class<T> listenerClass, T listener) {
        removeCallback(listenerClass, listener);
    }
    private final class ClosureProxy implements GSignalAPI.GSignalCallbackProxy {
        private final Closure closure;
        private final Method method;
        private final Class[] parameterTypes;
        NativeLong id;
        
        protected ClosureProxy(String signal, Closure closure) {
            this.closure = closure;

            Method invoke = null;
            for (Method m : closure.getClass().getDeclaredMethods()) {
                if (m.getName().equals(Closure.METHOD_NAME)) {
                    invoke = m;
                    break;
                }
            }
            if (invoke == null) {
                throw new IllegalArgumentException(closure.getClass() 
                        + " does not have an invoke method");
            }
            invoke.setAccessible(true);
            this.method = invoke;
            //
            // The closure does not have a 'user_data' pointer, so push it in as the 
            // last arg.  The last arg will be dropped later in callback()
            //
            parameterTypes = new Class[method.getParameterTypes().length + 1];
            parameterTypes[parameterTypes.length - 1] = Pointer.class;
            for (int i = 0; i < method.getParameterTypes().length; ++i) {
                Class<?> paramType = method.getParameterTypes()[i];
                Class<?> nativeType = paramType;
                if (ClockTime.class.isAssignableFrom(paramType)) {
                    nativeType = long.class;
                } else if (NativeObject.class.isAssignableFrom(paramType)) {
                    nativeType = Pointer.class;
                } else if (Enum.class.isAssignableFrom(paramType)) {
                    nativeType = int.class;
                } else if (String.class.isAssignableFrom(paramType)) {
                    nativeType = Pointer.class;
                } else if (Boolean.class.isAssignableFrom(paramType)) {
                    nativeType = int.class;
                }
                parameterTypes[i] = nativeType;
            }
            NativeLong connectID = gsignal.g_signal_connect_data(GObject.this, 
                    signal, this, null, null, 0);
            if (connectID.intValue() == 0) {
                throw new IllegalArgumentException(String.format("Failed to connect signal '%s'", signal));
            }
            this.id = connectID;
        }
        synchronized protected void disconnect() {
            if (id != null && id.intValue() != 0) {
                gobj.g_signal_handler_disconnect(GObject.this, id);
                id = null;
            }
        }
        @Override
        protected void finalize() {
            // Ensure the native callback is removed
            disconnect();
        }
        @SuppressWarnings("unchecked")
        public Object callback(Object[] parameters) {
            
            try {
                // Drop the last arg - it is the 'user_data' pointer
                Object[] methodParameters = new Object[parameters.length - 1];
            
                for (int i = 0; i < methodParameters.length; ++i) {
                    Class paramType = method.getParameterTypes()[i];
                    Object nativeParam = parameters[i];
                    Object javaParam = nativeParam;
                    if (nativeParam == null) {
                        continue;
                    }
                    if (ClockTime.class.isAssignableFrom(paramType)) {
                        javaParam = ClockTime.valueOf((Long) nativeParam, 
                                TimeUnit.NANOSECONDS);
                    } else if (NativeObject.class.isAssignableFrom(paramType)) {
                        javaParam = NativeObject.objectFor((Pointer) nativeParam, 
                                paramType, 1, true);
                    } else if (Enum.class.isAssignableFrom(paramType)) {
                        javaParam = EnumMapper.getInstance().valueOf((Integer) nativeParam, 
                                paramType);
                    } else if (String.class.isAssignableFrom(paramType)) {
                        javaParam = ((Pointer) nativeParam).getString(0);
                    } else if (Boolean.class.isAssignableFrom(paramType)) {
                        javaParam = Boolean.valueOf(((Integer) nativeParam).intValue() != 0);
                    } else {
                        javaParam = nativeParam;
                    }
                    methodParameters[i] = javaParam;
                }
                
                return method.invoke(closure, methodParameters);
            } catch (Throwable t) {
                return Integer.valueOf(0);
            }
        }

        public Class[] getParameterTypes() {
            return parameterTypes;
        }

        public Class getReturnType() {
            return method.getReturnType();
        }
    }
    public synchronized void connect(String signal, Closure closure) {
        final Map<String, Map<Closure, ClosureProxy>> signals = getClosureMap();
        Map<Closure, ClosureProxy> m = signals.get(signal);
        if (m == null) {
            m = new HashMap<Closure, ClosureProxy>();
            signals.put(signal, m);
        }
        m.put(closure, new ClosureProxy(signal, closure));
    }
    public synchronized void disconnect(String signal, Closure closure) {
        final Map<String, Map<Closure, ClosureProxy>> signals = signalClosures;
        if (signals == null) {
            return;
        }
        Map<Closure, ClosureProxy> map = signals.get(signal);
        if (map != null) {
            ClosureProxy cb = map.remove(signal);
            if (cb != null) {
                cb.disconnect();
            }
            if (map.isEmpty()) {
                signals.remove(signal);
                if (signalClosures.isEmpty()) {
                    signalClosures = null;
                }
            }
        }
    }
    
    public static GObject objectFor(Pointer ptr, Class<? extends GObject> defaultClass) {
        return GObject.objectFor(ptr, defaultClass, true);
    }
    
    public static <T extends GObject> T objectFor(Pointer ptr, Class<T> defaultClass, boolean needRef) {
        logger.entering("GObject", "objectFor", new Object[] { ptr, defaultClass, needRef });
        return NativeObject.objectFor(ptr, defaultClass, needRef);        
    }

    private GObjectAPI.GParamSpec findProperty(String propertyName) {
        return gobj.g_object_class_find_property(handle().getPointer(0), propertyName);
    }
    /*
     * Hooks to/from native disposal
     */
    private static final GObjectAPI.GToggleNotify toggle = new GObjectAPI.GToggleNotify() {
        public void callback(Pointer data, Pointer ptr, boolean is_last_ref) {
            
            /*
             * Manage the strong reference to this instance.  When this is the last
             * reference to the underlying object, remove the strong reference so
             * it can be garbage collected.  If it is owned by someone else, then make
             * it a strong ref, so the java GObject for the underlying C object can
             * be retained for later retrieval
             */
            GObject o = (GObject) NativeObject.instanceFor(ptr);
            if (o == null) {
                return;
            }
            logger.log(LIFECYCLE, "toggle_ref " + o.getClass().getSimpleName() +
                    " (" +  ptr + ")" + " last_ref=" + is_last_ref);
            if (is_last_ref) {
                strongReferences.remove(o);
            } else {
                strongReferences.put(o, Boolean.TRUE);
            }
        }
    };
}