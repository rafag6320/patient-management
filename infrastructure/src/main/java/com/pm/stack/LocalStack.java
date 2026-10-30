package com.pm.stack;

import java.util.*;
import software.amazon.awscdk.*;
import software.amazon.awscdk.customresources.*;
import software.amazon.awscdk.services.ec2.*;
import software.amazon.awscdk.services.ecs.*;
import software.amazon.awscdk.services.ecs.patterns.ApplicationLoadBalancedFargateService;
import software.amazon.awscdk.services.elasticloadbalancingv2.*;
import software.amazon.awscdk.services.certificatemanager.Certificate;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.kms.Key;
import software.amazon.awscdk.services.logs.*;
import software.amazon.awscdk.services.msk.CfnCluster;
import software.amazon.awscdk.services.rds.*;
import software.amazon.awscdk.services.secretsmanager.*;
import software.amazon.awscdk.services.servicediscovery.DnsRecordType;
import software.constructs.Construct;

/** Synthesizable AWS candidate. No AWS calls happen during construction/synthesis. */
public class LocalStack extends software.amazon.awscdk.Stack {
    private final Vpc vpc;
    private final software.amazon.awscdk.services.ecs.Cluster cluster;
    private final Map<String,DatabaseInstance> databases=new HashMap<>();
    private final Map<String,FargateService> services=new HashMap<>();
    private final ISecret jwt;
    private final ISecret producer;
    private final ISecret consumer;
    private final String brokers;
    private final CfnParameter desired;

    public LocalStack(Construct scope,String id,StackProps props) {
        super(scope,id,props);
        vpc=Vpc.Builder.create(this,"PatientManagementVPC").maxAzs(2).natGateways(1)
            .subnetConfiguration(List.of(SubnetConfiguration.builder().name("public").subnetType(SubnetType.PUBLIC).build(),
                SubnetConfiguration.builder().name("application").subnetType(SubnetType.PRIVATE_WITH_EGRESS).build(),
                SubnetConfiguration.builder().name("data").subnetType(SubnetType.PRIVATE_ISOLATED).build())).build();
        cluster=software.amazon.awscdk.services.ecs.Cluster.Builder.create(this,"PatientManagementECSCluster").vpc(vpc)
            .defaultCloudMapNamespace(CloudMapNamespaceOptions.builder().name("patient-management.local").build()).build();
        desired=CfnParameter.Builder.create(this,"ServiceDesiredCount").type("Number").defaultValue(1).minValue(0).maxValue(2).build();
        String jwtArn=CfnParameter.Builder.create(this,"JwtSecretArn").type("String").allowedPattern("arn:aws[a-z-]*:secretsmanager:.*").description("Existing secret with JSON key secret; never a plaintext JWT key").build().getValueAsString();
        jwt=software.amazon.awscdk.services.secretsmanager.Secret.fromSecretCompleteArn(this,"JwtSecret",jwtArn);
        var key=Key.Builder.create(this,"KafkaSecretKey").enableKeyRotation(true).removalPolicy(RemovalPolicy.RETAIN).build();
        producer=scramSecret("Publisher","patient_publisher",key);consumer=scramSecret("Consumer","analytics_consumer",key);
        var brokerSg=SecurityGroup.Builder.create(this,"BrokerSecurityGroup").vpc(vpc).build();
        var kafka=CfnCluster.Builder.create(this,"MskCluster").clusterName("patient-management")
            .kafkaVersion("3.9.x").numberOfBrokerNodes(2)
            .brokerNodeGroupInfo(CfnCluster.BrokerNodeGroupInfoProperty.builder().instanceType("kafka.m5.large")
                .clientSubnets(vpc.selectSubnets(SubnetSelection.builder().subnetType(SubnetType.PRIVATE_ISOLATED).build()).getSubnetIds())
                .securityGroups(List.of(brokerSg.getSecurityGroupId())).build())
            .clientAuthentication(CfnCluster.ClientAuthenticationProperty.builder()
                .sasl(CfnCluster.SaslProperty.builder().scram(CfnCluster.ScramProperty.builder().enabled(true).build()).build())
                .unauthenticated(CfnCluster.UnauthenticatedProperty.builder().enabled(false).build()).build())
            .encryptionInfo(CfnCluster.EncryptionInfoProperty.builder().encryptionInTransit(CfnCluster.EncryptionInTransitProperty.builder()
                .clientBroker("TLS").inCluster(true).build()).build()).build();
        kafka.applyRemovalPolicy(RemovalPolicy.RETAIN);
        var association=CfnResource.Builder.create(this,"ScramAssociation").type("AWS::MSK::BatchScramSecret")
            .properties(Map.of("ClusterArn",kafka.getAttrArn(),"SecretArnList",List.of(producer.getSecretArn(),consumer.getSecretArn()))).build();
        association.addDependency(kafka);
        var discover=AwsCustomResource.Builder.create(this,"BrokerDiscovery")
            .onUpdate(AwsSdkCall.builder().service("Kafka").action("getBootstrapBrokers").parameters(Map.of("ClusterArn",kafka.getAttrArn()))
                .physicalResourceId(PhysicalResourceId.of(kafka.getAttrArn())).build())
            .policy(AwsCustomResourcePolicy.fromSdkCalls(SdkCallsPolicyOptions.builder().resources(List.of(kafka.getAttrArn())).build()))
            .installLatestAwsSdk(false).build();
        discover.getNode().addDependency(kafka);
        brokers=discover.getResponseField("BootstrapBrokerStringSaslScram");
        for(String module:List.of("patient-service","auth-service","billing-service","analytics-service")) {
            databases.put(module,database(module,module.replace("-service","")+"_db"));
        }
        var auth=service("auth-service",4005,Map.of(),Map.of("JWT_SECRET",software.amazon.awscdk.services.ecs.Secret.fromSecretsManager(jwt,"secret")));
        var billing=service("billing-service",4001,Map.of("GRPC_SERVER_PORT","9001"),Map.of());
        var analytics=service("analytics-service",4002,Map.of("SPRING_KAFKA_BOOTSTRAP_SERVERS",brokers),kafkaSecrets(consumer));
        var patient=service("patient-service",4000,Map.of("BILLING_SERVICE_ADDRESS","billing-service.patient-management.local","BILLING_SERVICE_PORT","9001","SPRING_KAFKA_BOOTSTRAP_SERVERS",brokers),kafkaSecrets(producer));
        patient.getNode().addDependency(association);analytics.getNode().addDependency(association);
        billing.getConnections().allowFrom(patient,Port.tcp(9001));
        brokerSg.addIngressRule(patient.getConnections().getSecurityGroups().getFirst(),Port.tcp(9096));
        brokerSg.addIngressRule(analytics.getConnections().getSecurityGroups().getFirst(),Port.tcp(9096));
        var gatewayTask=task("api-gateway",4004,Map.of("AUTH_SERVICE_ADDRESS","http://auth-service.patient-management.local:4005",
            "PATIENT_SERVICE_ADDRESS","http://patient-service.patient-management.local:4000","ANALYTICS_SERVICE_ADDRESS","http://analytics-service.patient-management.local:4002"),Map.of());
        String certificate=CfnParameter.Builder.create(this,"CertificateArn").type("String").allowedPattern("arn:aws[a-z-]*:acm:.*").build().getValueAsString();
        var gateway=ApplicationLoadBalancedFargateService.Builder.create(this,"APIGatewayService").cluster(cluster).taskDefinition(gatewayTask)
            .serviceName("api-gateway").desiredCount(desired.getValueAsNumber()).assignPublicIp(false)
            .taskSubnets(SubnetSelection.builder().subnetType(SubnetType.PRIVATE_WITH_EGRESS).build())
            .certificate(Certificate.fromCertificateArn(this,"Certificate",certificate)).protocol(ApplicationProtocol.HTTPS).redirectHttp(true)
            .healthCheckGracePeriod(Duration.seconds(90)).build();
        gateway.getService().getConnections().allowFrom(gateway.getLoadBalancer(),Port.tcp(4004));
        auth.getConnections().allowFrom(gateway.getService(),Port.tcp(4005));patient.getConnections().allowFrom(gateway.getService(),Port.tcp(4000));analytics.getConnections().allowFrom(gateway.getService(),Port.tcp(4002));
        gateway.getTargetGroup().configureHealthCheck(software.amazon.awscdk.services.elasticloadbalancingv2.HealthCheck.builder().path("/actuator/health").healthyHttpCodes("200").build());
        // CDK 2.178's custom-resource provider defaults to Node 18/20, both obsolete in 2026.
        for(var child:getNode().findAll()) {
            if(child instanceof software.amazon.awscdk.services.lambda.CfnFunction function) {function.setRuntime("nodejs22.x");}
        }
        CfnOutput.Builder.create(this,"GatewayDns").value(gateway.getLoadBalancer().getLoadBalancerDnsName()).build();
        CfnOutput.Builder.create(this,"KafkaClusterArn").value(kafka.getAttrArn()).build();
        CfnOutput.Builder.create(this,"KafkaBrokersScram").value(brokers).build();
    }
    private ISecret scramSecret(String id,String username,Key key) {
        var secret=software.amazon.awscdk.services.secretsmanager.Secret.Builder.create(this,id+"Credentials")
            .secretName("AmazonMSK_"+getStackName()+"_"+id).encryptionKey(key)
            .generateSecretString(SecretStringGenerator.builder().secretStringTemplate("{\"username\":\""+username+"\"}")
                .generateStringKey("password").passwordLength(40).excludeCharacters("\"\\").build()).build();
        secret.applyRemovalPolicy(RemovalPolicy.RETAIN);return secret;
    }
    private Map<String,software.amazon.awscdk.services.ecs.Secret> kafkaSecrets(ISecret secret) {
        return Map.of("KAFKA_USERNAME",software.amazon.awscdk.services.ecs.Secret.fromSecretsManager(secret,"username"),"KAFKA_PASSWORD",software.amazon.awscdk.services.ecs.Secret.fromSecretsManager(secret,"password"));
    }
    private DatabaseInstance database(String id,String name) {
        return DatabaseInstance.Builder.create(this,id+"DB").vpc(vpc)
            .vpcSubnets(SubnetSelection.builder().subnetType(SubnetType.PRIVATE_ISOLATED).build())
            .engine(DatabaseInstanceEngine.postgres(PostgresInstanceEngineProps.builder().version(PostgresEngineVersion.of("16.15","16")).build()))
            .instanceType(software.amazon.awscdk.services.ec2.InstanceType.of(InstanceClass.BURSTABLE3,InstanceSize.SMALL)).allocatedStorage(20).maxAllocatedStorage(100)
            .databaseName(name).credentials(Credentials.fromGeneratedSecret("admin_user")).storageEncrypted(true).multiAz(true)
            .publiclyAccessible(false).backupRetention(Duration.days(14)).deleteAutomatedBackups(false).deletionProtection(true)
            .removalPolicy(RemovalPolicy.SNAPSHOT).build();
    }
    private FargateTaskDefinition task(String name,int port,Map<String,String> extra,Map<String,software.amazon.awscdk.services.ecs.Secret> extraSecrets) {
        var definition=FargateTaskDefinition.Builder.create(this,name+"Task").cpu(512).memoryLimitMiB(1024).build();
        var environment=new HashMap<String,String>(extra);environment.put("SPRING_PROFILES_ACTIVE","prod,aws");environment.put("SERVER_PORT",Integer.toString(port));
        var secrets=new HashMap<String,software.amazon.awscdk.services.ecs.Secret>(extraSecrets);
        var db=databases.get(name);
        if(db!=null) {
            environment.put("SPRING_DATASOURCE_URL","jdbc:postgresql://"+db.getDbInstanceEndpointAddress()+":"+db.getDbInstanceEndpointPort()+"/"+name.replace("-service","")+"_db?sslmode=verify-full&sslrootcert=/app/certs/global-bundle.pem");
            secrets.put("SPRING_DATASOURCE_USERNAME",software.amazon.awscdk.services.ecs.Secret.fromSecretsManager(db.getSecret(),"username"));
            secrets.put("SPRING_DATASOURCE_PASSWORD",software.amazon.awscdk.services.ecs.Secret.fromSecretsManager(db.getSecret(),"password"));
        }
        String digest=CfnParameter.Builder.create(this,name+"Digest").type("String").allowedPattern("sha256:[a-f0-9]{64}").description("Immutable published ECR digest").build().getValueAsString();
        var repo=Repository.fromRepositoryName(this,name+"Repository","patient-management/"+name);
        var logs=LogGroup.Builder.create(this,name+"Logs").retention(RetentionDays.ONE_MONTH).removalPolicy(RemovalPolicy.RETAIN).build();
        var ports=new ArrayList<PortMapping>();ports.add(PortMapping.builder().containerPort(port).build());
        if(name.equals("billing-service")){ports.add(PortMapping.builder().containerPort(9001).build());}
        definition.addContainer(name,ContainerDefinitionOptions.builder().image(ContainerImage.fromRegistry(repo.getRepositoryUri()+"@"+digest))
            .environment(environment).secrets(secrets).portMappings(ports)
            .logging(LogDriver.awsLogs(AwsLogDriverProps.builder().logGroup(logs).streamPrefix(name).build()))
            .healthCheck(software.amazon.awscdk.services.ecs.HealthCheck.builder().command(List.of("CMD","java","-cp","/probe","ReadinessCheck","http://localhost:"+port+"/actuator/health"))
                .interval(Duration.seconds(30)).timeout(Duration.seconds(5)).startPeriod(Duration.seconds(60)).build()).build());
        repo.grantPull(definition.obtainExecutionRole());
        return definition;
    }
    private FargateService service(String name,int port,Map<String,String> env,Map<String,software.amazon.awscdk.services.ecs.Secret> secrets) {
        var result=FargateService.Builder.create(this,name+"Service").serviceName(name).cluster(cluster).taskDefinition(task(name,port,env,secrets))
            .desiredCount(desired.getValueAsNumber()).assignPublicIp(false).cloudMapOptions(CloudMapOptions.builder().name(name).dnsRecordType(DnsRecordType.A).dnsTtl(Duration.seconds(10)).build())
            .vpcSubnets(SubnetSelection.builder().subnetType(SubnetType.PRIVATE_WITH_EGRESS).build())
            .circuitBreaker(DeploymentCircuitBreaker.builder().rollback(true).build()).build();
        databases.get(name).getConnections().allowDefaultPortFrom(result);services.put(name,result);return result;
    }
}
