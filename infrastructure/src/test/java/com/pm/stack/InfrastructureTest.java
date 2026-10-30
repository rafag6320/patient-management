package com.pm.stack;
import org.junit.jupiter.api.*;
import software.amazon.awscdk.*;
import software.amazon.awscdk.assertions.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class InfrastructureTest {
    private Template candidate() {
        var app=new App();var stack=new LocalStack(app,"test-candidate",StackProps.builder().build());return Template.fromStack(stack);
    }
    @Test void ownsFourProtectedDatabasesAndFivePrivateServices() {
        var template=candidate();template.resourceCountIs("AWS::RDS::DBInstance",4);template.resourceCountIs("AWS::ECS::Service",5);
        template.allResourcesProperties("AWS::RDS::DBInstance",Match.objectLike(Map.of("PubliclyAccessible",false,"StorageEncrypted",true,"MultiAZ",true,"DeletionProtection",true,"BackupRetentionPeriod",14)));
        template.resourceCountIs("AWS::Route53::HealthCheck",0);
        template.allResourcesProperties("AWS::ECS::Service",Match.objectLike(Map.of("NetworkConfiguration",Match.objectLike(Map.of("AwsvpcConfiguration",Match.objectLike(Map.of("AssignPublicIp","DISABLED")))))));
    }
    @Test void taskDefinitionsUseSecretsDigestsAndValidDatasourceNames() {
        var template=candidate();template.resourceCountIs("AWS::ECS::TaskDefinition",5);
        String json=template.toJSON().toString();assertFalse(json.contains("SPRING_DATASOURSE"));assertFalse(json.contains("host.docker.internal"));assertFalse(json.contains(":latest"));assertTrue(json.contains("@"),"Images must reference digests with @, never tag:sha256");
        assertTrue(json.contains("sslmode=verify-full"));assertTrue(json.contains("SPRING_DATASOURCE_PASSWORD"));assertTrue(json.contains("Secrets"));
        assertTrue(json.contains("BILLING_SERVICE_PORT"));assertFalse(json.contains("BILLING_SERVICE_GRPC_PORT"));
        assertFalse(json.contains("my-secret-key"));assertFalse(json.contains("SPRING_SQL_INIT_MODE"));
    }
    @Test void kafkaUsesTwoBrokersTlsAndScramInsteadOfLocalAddresses() {
        var template=candidate();template.hasResourceProperties("AWS::MSK::Cluster",Match.objectLike(Map.of("NumberOfBrokerNodes",2,"KafkaVersion","3.9.x",
            "ClientAuthentication",Match.objectLike(Map.of("Sasl",Match.objectLike(Map.of("Scram",Map.of("Enabled",true))),"Unauthenticated",Map.of("Enabled",false))),
            "EncryptionInfo",Match.objectLike(Map.of("EncryptionInTransit",Map.of("ClientBroker","TLS","InCluster",true))))));
        template.allResourcesProperties("AWS::Lambda::Function",Match.objectLike(Map.of("Runtime","nodejs22.x")));
        template.resourceCountIs("AWS::MSK::BatchScramSecret",1);template.resourceCountIs("AWS::ElasticLoadBalancingV2::Listener",2);
        template.hasResourceProperties("AWS::ElasticLoadBalancingV2::Listener",Match.objectLike(Map.of("Port",443,"Protocol","HTTPS")));
    }
    @Test void registryRetainsFiveImmutableScannedRepositories() {
        var template=Template.fromStack(new RegistryStack(new App(),"test-registry",StackProps.builder().build()));
        template.resourceCountIs("AWS::ECR::Repository",5);template.allResourcesProperties("AWS::ECR::Repository",Match.objectLike(Map.of("ImageTagMutability","IMMUTABLE","ImageScanningConfiguration",Map.of("ScanOnPush",true))));
    }
    @Test void emulationScopeOnlyContainsSyntheticSecret() {
        var template=Template.fromStack(new EmulationStack(new App(),"test-emulation",StackProps.builder().synthesizer(new BootstraplessSynthesizer()).build()));
        template.resourceCountIs("AWS::SecretsManager::Secret",1);template.resourceCountIs("AWS::RDS::DBInstance",0);template.resourceCountIs("AWS::MSK::Cluster",0);template.resourceCountIs("AWS::ECS::Service",0);
    }
}
