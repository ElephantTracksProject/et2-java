import java.util.concurrent.atomic.AtomicInteger;
import java.util.Arrays;
import java.io.PrintWriter;
import java.util.concurrent.locks.ReentrantLock;

public class ETProxy {
    
    private static boolean atMain = false;

    // Thread local boolean w/ default value false
    private static final InstrumentFlag inInstrumentMethod = new InstrumentFlag();

    private static final int BUFMAX = 1000;

    private static long[] timestampBuffer = new long[BUFMAX];
    private static long[] threadIDBuffer = new long[BUFMAX];

    // TRACING EVENTS
    // Method entry = 1, method exit = 2, object allocation = 3
    // object array allocation = 4
    // 2D array allocation = 6, put field = 7
    // WITNESSES
    // get field = 8

    private static int[] eventTypeBuffer = new int[BUFMAX];
    
    private static int[] firstBuffer = new int[BUFMAX];
    private static int[] secondBuffer = new int[BUFMAX];
    private static int[] thirdBuffer = new int[BUFMAX];
    private static int[] fourthBuffer = new int[BUFMAX];
    private static int[] fifthBuffer = new int[BUFMAX];
    private static AtomicInteger ptr = new AtomicInteger();
    private static ReentrantLock mx = new ReentrantLock();
    private static PrintWriter pw;

    static {
        try {
            pw = new PrintWriter("trace");
        } catch (Exception e) {
            System.err.println("FNF");
        }
    }

    // static {
    //     try {
    //         System.loadLibrary("objectsize");
    //         System.err.println("object size library loaded");
    //     } catch (Exception e) {
    //         System.err.println(e.getMessage());
    //         System.err.println("Error: can't load libobjectsize.so");
    //     }
    // }
    
    // I hope no one ever creates a 2 gigabyte object
    private static native int getObjectSize(Object obj);

    // shh.. pretend I didn't do anything bad
    // private static Unsafe unsafe = getUnsafe();

    // private static Unsafe getUnsafe() {
    //     try {
    //         Field f = Unsafe.class.getDeclaredField("theUnsafe");
    //         f.setAccessible(true);
    //         return (Unsafe) f.get(null);
    //     } catch (Exception e) {
    //         return null;
    //     }
    // }

    // See: https://highlyscalable.wordpress.com/2012/02/02/direct-memory-access-in-java/
    // private static long getObjectSize(Object object) {
    //     System.err.println(object);
    //     return unsafe.getAddress(_normalize(unsafe.getInt(object, 4L)) + 12L);
    // }
 
    // private static long _normalize(int value) {
    //     if(value >= 0) return value;
    //     return (~0L >>> 32) & value;
    // }
    
    // Adapted from JNIF test code
    private static byte[] getResourceEx(String className, ClassLoader loader) {
        java.io.InputStream is;

        try {
            is = loader.getResourceAsStream(className + ".class");
        } catch (Throwable e) {
            // System.err.println("Error: getResource for " + className);
            // System.err.println("Error: loader: " + loader);
            // e.printStackTrace();
            return null;
        }

        java.io.ByteArrayOutputStream os = new java.io.ByteArrayOutputStream();
        try {
            byte[] buffer = new byte[0xFFFF];

            for (int len; (len = is.read(buffer)) != -1;) {
                os.write(buffer, 0, len);
            }

            os.flush();

            return os.toByteArray();
        } catch (Throwable e) {
            // System.err.println("Error: getResource for " + className);
            // System.err.println("Error: loader: " + loader);
            // System.err.println("Error: is: " + is);
            // e.printStackTrace();
            return null;
        }
    }

    public static byte[] getResource(String className, ClassLoader loader) {
        byte[] res = null;

        // System.err.println(className + " requested");
        
        if (loader != null) {
            res = getResourceEx(className, loader);
        }

        if (res == null) {
            ClassLoader cl = ClassLoader.getSystemClassLoader();
            res = getResourceEx(className, cl);
        }

        return res;
    }

    // TODO: What was this for?
    // public static native void _onMain();
    
    public static void onEntry(int methodID, Object receiver)
    {
        if (!atMain) {
            return;
        }
        
        long timestamp = System.nanoTime();
        
        if (inInstrumentMethod.get()) {
            return;
        } else {
            inInstrumentMethod.set(true);
        }

        mx.lock();
        try {
            while (true) {
                if (ptr.get() < BUFMAX) {
                    // wait on ptr to prevent overflow
                    int currPtr;
                    synchronized(ptr) {
                        currPtr = ptr.getAndIncrement();
                    }
                    firstBuffer[currPtr] = methodID;
                    secondBuffer[currPtr] = (receiver == null) ? 0 : System.identityHashCode(receiver);
                    eventTypeBuffer[currPtr] = 1;
                    timestampBuffer[currPtr] = timestamp;
                    threadIDBuffer[currPtr] = System.identityHashCode(Thread.currentThread());
                    break;
                } else {
                    synchronized(ptr) {
                        if (ptr.get() >= BUFMAX) {
                            flushBuffer();
                        }
                    }
                }
            }
            // System.out.println("Method ID: " + methodID);

            
        } finally {
            mx.unlock();
        }

        inInstrumentMethod.set(false);
    }

    public static void onExit(int methodID)
    {
        if (!atMain) {
            return;
        }
        
        long timestamp = System.nanoTime();
        
        if (inInstrumentMethod.get()) {
            return;
        } else {
            inInstrumentMethod.set(true);
        }

        mx.lock();
        try {
            while (true) {
                if (ptr.get() < BUFMAX) {
                    // wait on ptr to prevent overflow
                    int currPtr;
                    synchronized(ptr) {
                        currPtr = ptr.getAndIncrement();
                    }

                    firstBuffer[currPtr] = methodID;
                    eventTypeBuffer[currPtr] = 2;
                    timestampBuffer[currPtr] = timestamp;
                    threadIDBuffer[currPtr] = System.identityHashCode(Thread.currentThread());
                    break;
                } else {
                    synchronized(ptr) {
                        if (ptr.get() >= BUFMAX) {
                            flushBuffer();
                        }
                    }
                }
            }
        } finally {
            mx.unlock();
        }
        // System.out.println("Method ID: " + methodID);

        inInstrumentMethod.set(false);
    }
    
    public static void onObjectAlloc(Object allocdObject, int allocdClassID, int allocSiteID)
    {

        if (!atMain) {
            return;
        }
        long timestamp = System.nanoTime();
        if (inInstrumentMethod.get()) {
            return;
        } else {
            inInstrumentMethod.set(true);
        }
        mx.lock();
        try {
            while (true) {
                if (ptr.get() < BUFMAX) {
                    // wait on ptr to prevent overflow
                    int currPtr;
                    synchronized(ptr) {
                        currPtr = ptr.getAndIncrement();
                    }
                    firstBuffer[currPtr] = System.identityHashCode(allocdObject);
                    eventTypeBuffer[currPtr] = 3;
                    secondBuffer[currPtr] = allocdClassID;
                    thirdBuffer[currPtr] = allocSiteID;
                    // I hope no one ever wants a 2 gigabyte (shallow size!) object
                    // some problem here...
                    // System.err.println("Class ID: " + allocdClassID);
                    fourthBuffer[currPtr] = (int) getObjectSize(allocdObject);
                    timestampBuffer[currPtr] = timestamp;
                    threadIDBuffer[currPtr] = System.identityHashCode(Thread.currentThread());
                    break;
                } else {
                    synchronized(ptr) {
                        if (ptr.get() >= BUFMAX) {
                            flushBuffer();
                        }
                    }
                }
            }
        } finally {
            mx.unlock();
        }
        
        inInstrumentMethod.set(false);
    }

    public static void onPutField(Object tgtObject, Object srcObject, int fieldID)
    {

        if (!atMain) {
            return;
        }
        
        long timestamp = System.nanoTime();
        
        if (inInstrumentMethod.get()) {
            return;
        } else {
            inInstrumentMethod.set(true);
        }

        mx.lock();

        try {
            while (true) {
                if (ptr.get() < BUFMAX) {
                    // wait on ptr to prevent overflow
                    int currPtr;
                    synchronized(ptr) {
                        currPtr = ptr.getAndIncrement();
                    }
                    firstBuffer[currPtr] = System.identityHashCode(tgtObject);
                    eventTypeBuffer[currPtr] = 7;
                    secondBuffer[currPtr] = fieldID;
                    timestampBuffer[currPtr] = timestamp;
                    thirdBuffer[currPtr] = System.identityHashCode(srcObject);
                    threadIDBuffer[currPtr] = System.identityHashCode(Thread.currentThread());
                    break;
                } else {
                    synchronized(ptr) {
                        if (ptr.get() >= BUFMAX) {
                            flushBuffer();
                        }
                    }
                }
            }
        } finally {
            mx.unlock();
        }
        
        inInstrumentMethod.set(false);
    }

    // TODO: Why is this commented out?
    //
    // public static void onPrimitiveArrayAlloc(int atype, int size, Object allocdArray)
    // {
    //     long timestamp = System.nanoTime();
        
    //     if (inInstrumentMethod.get()) {
    //         return;
    //     } else {
    //         inInstrumentMethod.set(true);
    //     }

    //     while (true) {
    //         if (ptr.get() < BUFMAX) {
    //             // wait on ptr to prevent overflow
    //             int currPtr = ptr.getAndIncrement();
    //             firstBuffer[currPtr] = System.identityHashCode(allocdArray);
    //             eventTypeBuffer[currPtr] = 5;
    //             secondBuffer[currPtr] = atype;
    //             thirdBuffer[currPtr] = size;
    //             timestampBuffer[currPtr] = timestamp;
    //             threadIDBuffer[currPtr] = System.identityHashCode(Thread.currentThread());
    //             break;
    //         } else {
    //             synchronized(ptr) {
    //                 if (ptr.get() >= BUFMAX) {
    //                     flushBuffer();
    //                 }
    //             }
    //         }
    //     }
        
    //     inInstrumentMethod.set(false);
    // }

    public static void onArrayAlloc( int allocdClassID,
                                     int size,
                                     Object allocdArray,
                                     int allocSiteID )
    {

        if (!atMain) {
            return;
        }
        
        long timestamp = System.nanoTime();
        
        if (inInstrumentMethod.get()) {
            return;
        } else {
            inInstrumentMethod.set(true);
        }

        mx.lock();

        try {
            while (true) {
                if (ptr.get() < BUFMAX) {
                    // wait on ptr to prevent overflow
                    int currPtr;
                    synchronized(ptr) {
                        currPtr = ptr.getAndIncrement();
                    }
                    firstBuffer[currPtr] = System.identityHashCode(allocdArray);
                    eventTypeBuffer[currPtr] = 4;
                    secondBuffer[currPtr] = allocdClassID;
                    thirdBuffer[currPtr] = size;
                    fourthBuffer[currPtr] = allocSiteID;
                    fifthBuffer[currPtr] = getObjectSize(allocdArray);
                    timestampBuffer[currPtr] = timestamp;
                    threadIDBuffer[currPtr] = System.identityHashCode(Thread.currentThread());
                    break;
                } else {
                    synchronized(ptr) {
                        if (ptr.get() >= BUFMAX) {
                            flushBuffer();
                        }
                    }
                }
            }
        } finally {
            mx.unlock();
        }
        
        inInstrumentMethod.set(false);
    }

    public static void onMultiArrayAlloc( int dims,
                                          int allocdClassID,
                                          Object[] allocdArray,
                                          int allocSiteID )
    {
        if (!atMain) {
            return;
        }
        
        long timestamp = System.nanoTime();
        
        if (inInstrumentMethod.get()) {
            return;
        } else {
            inInstrumentMethod.set(true);
        }

        mx.lock();

        try {
            while (true) {
                if (ptr.get() < BUFMAX) {
                    // wait on ptr to prevent overflow
                    int currPtr;
                    synchronized(ptr) {
                        currPtr = ptr.getAndIncrement();
                    }
                    firstBuffer[currPtr] = System.identityHashCode(allocdArray);
                    eventTypeBuffer[currPtr] = 6;
                    secondBuffer[currPtr] = allocdClassID;
                    thirdBuffer[currPtr] = allocdArray.length;
                    fourthBuffer[currPtr] = allocSiteID;
                    fifthBuffer[currPtr] = getObjectSize(allocdArray);
                    timestampBuffer[currPtr] = timestamp;
                    threadIDBuffer[currPtr] = System.identityHashCode(Thread.currentThread());

                    if (dims > 2) {
                        for (int i = 0; i < allocdArray.length; ++i) {
                            onMultiArrayAlloc(dims - 1, allocdClassID, (Object[]) allocdArray[i], allocSiteID);
                        }
                    } else { // dims == 2
                        for (int i = 0; i < allocdArray.length; ++i) {
                            onArrayAlloc(allocdClassID, 0, allocdArray[i], allocSiteID);
                        }
                    }
                    break;
                } else {
                    synchronized(ptr) {
                        if (ptr.get() >= BUFMAX) {
                            flushBuffer();
                        }
                    }
                }
            }
        } finally {
            mx.unlock();
        }
        
        inInstrumentMethod.set(false);
    }

    public static void witnessObjectAlive( Object aliveObject,
                                           int classID )
    {
        if (!atMain) {
            return;
        }
        
        long timestamp = System.nanoTime();
        
        if (inInstrumentMethod.get()) {
            return;
        } else {
            inInstrumentMethod.set(true);
        }

        mx.lock();

        try {
            while (true) {
                if (ptr.get() < BUFMAX) {
                    // wait on ptr to prevent overflow
                    int currPtr;
                    synchronized(ptr) {
                        currPtr = ptr.getAndIncrement();
                    }

                    // System.err.println("Seems like problem is here...");
                    // System.err.println("Class being instrumented here (ID): " + classID);

                    firstBuffer[currPtr] = System.identityHashCode(aliveObject);

                    // System.err.println("gets here");
                    eventTypeBuffer[currPtr] = 8;
                    secondBuffer[currPtr] = classID;
                    timestampBuffer[currPtr] = timestamp;

                    threadIDBuffer[currPtr] = System.identityHashCode(Thread.currentThread());
                    break;
                } else {
                    synchronized(ptr) {
                        if (ptr.get() >= BUFMAX) {
                            flushBuffer();
                        }
                    }
                }
            }
        } finally {
            mx.unlock();
        }
        
        inInstrumentMethod.set(false);
    }
    
    public static void flushBuffer()
    {
        mx.lock();
        try {
            for (int i = 0; i < BUFMAX; i++) {
                switch(eventTypeBuffer[i]) {
                    case 1: // method entry
                        // M <method-id> <receiver-object-id> <thread-id>
                        pw.println( "M " +
                                    firstBuffer[i] + " " +
                                    secondBuffer[i] + " " +
                                    threadIDBuffer[i] );
                        break;
                    case 2: // method exit
                        // E <method-id> <thread-id>
                        pw.println( "E " +
                                    firstBuffer[i] + " " +
                                    threadIDBuffer[i] );
                        break;
                    case 3: // object allocation
                        // N <object-id> <size> <type-id> <site-id> <length (0)> <thread-id>
                        // 1st buffer = object ID (hash)
                        // 2nd buffer = class ID
                        // 3rd buffer = allocation site (method ID)
                        pw.println( "N " +
                                    firstBuffer[i] + " " +
                                    fourthBuffer[i] + " " +
                                    secondBuffer[i] + " " +
                                    thirdBuffer[i] + " "
                                    + 0 + " "
                                    + threadIDBuffer[i] );
                        break;
                    case 4: // object array allocation
                    case 5: // primitive array allocation
                        // 5 now removed so nothing should come out of it
                        // A <object-id> <size> <type-id> <site-id> <length> <thread-id>
                        pw.println( "A " +
                                    firstBuffer[i] + " " +
                                    fifthBuffer[i] + " " +
                                    secondBuffer[i] + " " +
                                    fourthBuffer[i] + " " +
                                    thirdBuffer[i] + " " +
                                    threadIDBuffer[i] );
                        break;
                    case 6: // 2D array allocation
                        // TODO: Conflicting documention: 2018-1112
                        // 6, arrayHash, arrayClassID, size1, size2, timestamp
                        // A <object-id> <size> <type-id> <site-id> <length> <thread-id>
                        pw.println( "A " +
                                    firstBuffer[i] + " " +
                                    fifthBuffer[i] + " " +
                                    secondBuffer[i] + " " +
                                    fourthBuffer[i] + " " +
                                    thirdBuffer[i] + " " +
                                    threadIDBuffer[i] );
                        break;
                    case 7: // object update
                        // TODO: Conflicting documention: 2018-1112
                        // 7, targetObjectHash, fieldID, srcObjectHash, timestamp
                        // U <old-tgt-obj-id> <obj-id> <new-tgt-obj-id> <field-id> <thread-id>
                        pw.println( "U " +
                                    firstBuffer[i] + " " +
                                    secondBuffer[i] + " " +
                                    thirdBuffer[i] + " " +
                                    threadIDBuffer[i] );
                        break;
                    case 8: // witness with get field
                        // 8, aliveObjectHash, classID, timestamp
                        pw.println( "W" + " " +
                                    firstBuffer[i] + " " +
                                    secondBuffer[i] + " " +
                                    threadIDBuffer[i] );
                        break;
                }
            }
            ptr.set(0);
        } finally {
            mx.unlock();
        }
    }
    
}
