package org.codejargon.feather;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Qualifier;
import javax.inject.Singleton;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Feather {
    /**
     * Constructs the injector with configuration modules
     */
    public static Feather with(Object... modules) {
        return with(Arrays.asList(modules));
    }

    /**
     * Constructs the injector with configuration modules
     */
    public static Feather with(Iterable<?> modules) {
        return new Feather(modules);
    }


    private final Map<Key, Provider<?>> providers = new ConcurrentHashMap<>();
    private final Map<Key, Object> singletons = new ConcurrentHashMap<>();
    private final Map<Class, Object[][]> injectFields = new ConcurrentHashMap<>(0);

    private Feather(Iterable<?> modules) {
        providers.put(Key.of(Feather.class), new Provider() {
                    @Override
                    public Object get() {
                        return Feather.this;
                    }
                }
        );

        for (final Object module : modules) {
            if (module instanceof Class) {
                throw new FeatherException(String.format("%s provided as class instead of an instance.", ((Class) module).getName()));
            }
            for (Method providerMethod : findProviderMethods(module.getClass())) {
                registerProviderMethod(module, providerMethod);
            }
        }
    }

    /**
     * @return an instance of type
     */
    public <T> T instance(Class<T> type) {
        return getProvider(Key.of(type), null).get();
    }

    /**
     * @return instance specified by key (type and qualifier)
     */
    public <T> T instance(Key<T> key) {
        return getProvider(key, null).get();
    }

    /**
     * @return provider of type
     */
    public <T> Provider<T> provider(Class<T> type) {
        return getProvider(Key.of(type), null);
    }

    /**
     * @return provider of key (type, qualifier)
     */
    public <T> Provider<T> provider(Key<T> key) {
        return getProvider(key, null);
    }

    /**
     * Injects fields to the target object
     */
    public void injectFields(Object target) {
        if (!injectFields.containsKey(target.getClass())) {
            injectFields.put(target.getClass(), injectFields(target.getClass()));
        }
        for (Object[] f : injectFields.get(target.getClass())) {
            Field field = (Field) f[0];
            Key key = (Key) f[2];
            try {
                field.set(target, (boolean) f[1] ? provider(key) : instance(key));
            } catch (Exception e) {
                throw new FeatherException(String.format("Can't inject field %s in %s",
                        field.getName(),
                        target.getClass().getName()), e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private <T> Provider<T> getProvider(final Key<T> key, Set<Key> chain) {
        if (!providers.containsKey(key)) {

            final Constructor constructor = findInjectableConstructor(key);

            final Provider<?>[] paramProviders = getParamProviders(key,
                    constructor.getParameterTypes(),
                    constructor.getGenericParameterTypes(),
                    constructor.getParameterAnnotations(),
                    chain);

            providers.put(key, singletonProvider(key, key.type.getAnnotation(Singleton.class), new Provider() {
                        @Override
                        public Object get() {
                            try {
                                return constructor.newInstance(generateParams(paramProviders));
                            } catch (Exception e) {
                                throw new FeatherException(String.format("Can't instantiate %s", key.toString()), e);
                            }
                        }
                    })
            );
        }
        return (Provider<T>) providers.get(key);
    }

    private void registerProviderMethod(final Object module, final Method m) {
        final Key key = Key.of(m.getReturnType(), findQualifier(m.getAnnotations()));
        if (providers.containsKey(key)) {
            throw new FeatherException(String.format("%s has multiple providers, module %s", key.toString(), module.getClass()));
        }
        Singleton singleton = m.getAnnotation(Singleton.class) != null ? m.getAnnotation(Singleton.class)
                : m.getReturnType().getAnnotation(Singleton.class);

        final Provider<?>[] paramProviders = getParamProviders(
                key,
                m.getParameterTypes(),
                m.getGenericParameterTypes(),
                m.getParameterAnnotations(),
                Collections.singleton(key)
        );
        providers.put(key, singletonProvider(key, singleton, new Provider() {
                    @Override
                    public Object get() {
                        try {
                            return m.invoke(module, generateParams(paramProviders));
                        } catch (Exception e) {
                            throw new FeatherException(String.format("Can't instantiate %s with provider", key.toString()), e);
                        }
                    }
                }
                )
        );
    }

    @SuppressWarnings("unchecked")
    private <T> Provider<T> singletonProvider(final Key key, Singleton singleton, final Provider<T> provider) {
        return singleton != null ? new Provider<T>() {
            @Override
            public T get() {
                if (!singletons.containsKey(key)) {
                    synchronized (singletons) {
                        if (!singletons.containsKey(key)) {
                            singletons.put(key, provider.get());
                        }
                    }
                }
                return (T) singletons.get(key);
            }
        } : provider;
    }

    private Provider<?>[] getParamProviders(final Key key,
                                            Class<?>[] paramClasses, Type[] paramTypes, Annotation[][] paramAnnotations,
                                            final Set<Key> chain) {
        Provider<?>[] providers = new Provider<?>[paramTypes.length];
        for (int i = 0; i < paramTypes.length; ++i) {
            Class<?> parameterClass = paramClasses[i];
            Annotation qualifier = findQualifier(paramAnnotations[i]);
            Class<?> providerType = Provider.class.equals(parameterClass) ?
                    (Class<?>) ((ParameterizedType) paramTypes[i]).getActualTypeArguments()[0] :
                    null;
            if (providerType == null) {
                final Key newKey = Key.of(parameterClass, qualifier);
                final Set<Key> newChain = appendKey(chain, key);
                if (newChain.contains(newKey)) {
                    throw new FeatherException(String.format("Circular dependency: %s", renderChain(newChain, newKey)));
                }
                providers[i] = new Provider() {
                    @Override
                    public Object get() {
                        return getProvider(newKey, newChain).get();
                    }
                };
            } else {
                final Key newKey = Key.of(providerType, qualifier);
                providers[i] = new Provider() {
                    @Override
                    public Object get() {
                        return getProvider(newKey, null);
                    }
                };
            }
        }
        return providers;
    }

    private static Object[] generateParams(Provider<?>[] paramProviders) {
        Object[] params = new Object[paramProviders.length];
        for (int i = 0; i < paramProviders.length; ++i) {
            params[i] = paramProviders[i].get();
        }
        return params;
    }

    private static Set<Key> appendKey(Set<Key> set, Key newKey) {
        if (set != null && !set.isEmpty()) {
            Set<Key> appended = new LinkedHashSet<>(set);
            appended.add(newKey);
            return appended;
        } else {
            return Collections.singleton(newKey);
        }
    }

    private static Object[][] injectFields(Class<?> target) {
        Set<Field> fields = findInjectableFields(target);
        Object[][] fs = new Object[fields.size()][];
        int i = 0;
        for (Field f : fields) {
            Class<?> providerType = f.getType().equals(Provider.class) ?
                    (Class<?>) ((ParameterizedType) f.getGenericType()).getActualTypeArguments()[0] :
                    null;
            fs[i++] = new Object[]{
                    f,
                    providerType != null,
                    Key.of(providerType != null ? providerType : f.getType(), findQualifier(f.getAnnotations()))
            };
        }
        return fs;
    }

    private static Set<Field> findInjectableFields(Class<?> type) {
        Class<?> current = type;
        Set<Field> fields = new HashSet<>();
        while (!current.equals(Object.class)) {
            for (Field field : current.getDeclaredFields()) {
                if (field.isAnnotationPresent(Inject.class)) {
                    field.setAccessible(true);
                    fields.add(field);
                }
            }
            current = current.getSuperclass();
        }
        return fields;
    }

    private static String renderChain(Set<Key> chain, Key lastKey) {
        StringBuilder chainString = new StringBuilder();
        for (Key key : chain) {
            chainString.append(key.toString()).append(" -> ");
        }
        return chainString.append(lastKey.toString()).toString();
    }

    private static Constructor findInjectableConstructor(Key key) {
        List<Constructor> publicConstructors = new ArrayList<>();
        List<Constructor> injectConstructors = new ArrayList<>();

        for (Constructor constructor : key.type.getDeclaredConstructors()) {
            if (Modifier.isPublic(constructor.getModifiers())) {
                publicConstructors.add(constructor);
            }
            if (constructor.isAnnotationPresent(Inject.class)) {
                injectConstructors.add(constructor);
            }
        }

        Constructor selectedConstructor;

        if (injectConstructors.size() == 0 && publicConstructors.size() == 1) {
            selectedConstructor = publicConstructors.get(0);
        } else if (injectConstructors.size() == 1) {
            selectedConstructor = injectConstructors.get(0);
            selectedConstructor.setAccessible(true);
        } else {
            if (injectConstructors.size() > 1) {
                throw new FeatherException(String.format("%s has multiple @Inject constructors", key.type));
            } else {
                throw new FeatherException(String.format("%s has no valid injection constructor", key.type));
            }
        }

        return selectedConstructor;
    }

    private static Set<Method> findProviderMethods(Class<?> type) {
        Class<?> current = type;
        Set<Method> providers = new HashSet<>();
        while (!current.equals(Object.class)) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Provides.class) && (type.equals(current) || !containsMethod(providers, method))) {
                    method.setAccessible(true);
                    providers.add(method);
                }
            }
            current = current.getSuperclass();
        }
        return providers;
    }

    private static Annotation findQualifier(Annotation[] annotations) {
        for (Annotation annotation : annotations) {
            if (annotation.annotationType().isAnnotationPresent(Qualifier.class)) {
                return annotation;
            }
        }
        return null;
    }

    private static boolean containsMethod(Set<Method> discoveredMethods, Method method) {
        for (Method discovered : discoveredMethods) {
            if (discovered.getName().equals(method.getName()) && Arrays.equals(method.getParameterTypes(),
                    discovered.getParameterTypes())) {
                return true;
            }
        }
        return false;
    }

}