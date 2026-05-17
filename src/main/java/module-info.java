module cloudsimplus {
    requires ch.qos.logback.classic;
    requires com.cronutils;
    requires com.google.gson;
    requires commons.math3;
    requires static lombok;
    requires org.apache.commons.collections4;
    requires org.apache.commons.lang3;
    requires org.jetbrains.annotations;
    requires org.slf4j;

    // Kubernetes simulation layer — public API for downstream consumers.
    exports org.cloudsimplus.kubernetes;
    exports org.cloudsimplus.kubernetes.builders;
    exports org.cloudsimplus.kubernetes.controllers;
    exports org.cloudsimplus.kubernetes.lifecycle;
    exports org.cloudsimplus.kubernetes.scheduler;
    exports org.cloudsimplus.kubernetes.autoscaling;
    exports org.cloudsimplus.kubernetes.networking;
    exports org.cloudsimplus.kubernetes.storage;
    exports org.cloudsimplus.kubernetes.security;
}
