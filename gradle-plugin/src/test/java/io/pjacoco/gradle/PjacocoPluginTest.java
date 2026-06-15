package io.pjacoco.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

class PjacocoPluginTest {

    private Project applied() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("io.pjacoco.gradle");
        return project;
    }

    @Test
    void registersExtensionWithConventions() {
        Project project = applied();
        PjacocoGradleExtension ext = project.getExtensions().getByType(PjacocoGradleExtension.class);
        assertEquals(6310, ext.getPort().get());
        assertEquals(PjacocoPlugin.DEFAULT_AGENT_VERSION, ext.getAgentVersion().get());
        assertTrue(ext.getDestfile().get().getAsFile().getPath().endsWith("pjacoco"),
                "destfile defaults under build/pjacoco");
    }

    @Test
    void createsResolvableAgentConfiguration() {
        Project project = applied();
        assertNotNull(project.getConfigurations().findByName(PjacocoPlugin.CONFIGURATION_NAME));
        assertTrue(project.getConfigurations().getByName(PjacocoPlugin.CONFIGURATION_NAME).isCanBeResolved());
    }

    @Test
    void controlUrlArgIsPopulatedFromPort() {
        Project project = applied();
        PjacocoGradleExtension ext = project.getExtensions().getByType(PjacocoGradleExtension.class);
        ext.getPort().set(6399);
        assertEquals("-Dpjacoco.control-url=http://127.0.0.1:6399", ext.getControlUrlArg().get());
    }
}
