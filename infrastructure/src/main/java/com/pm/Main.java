package com.pm;
import com.pm.stack.*;
import software.amazon.awscdk.*;
public class Main {
    public static void main(String[] args) {
        String out=System.getProperty("pm.synth.directory","cdk.out");
        var app=new App(AppProps.builder().outdir(out).build());
        new RegistryStack(app,"patient-management-registry",StackProps.builder().terminationProtection(true).build());
        new LocalStack(app,"patient-management-candidate",StackProps.builder().terminationProtection(true).build());
        new EmulationStack(app,"patient-management-emulation",StackProps.builder().synthesizer(new BootstraplessSynthesizer()).build());
        app.synth();System.out.println("Synthesized review artifacts: "+out);
    }
}
