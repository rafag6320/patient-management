package com.pm.stack;
import software.amazon.awscdk.*;
import software.amazon.awscdk.services.ecr.*;
import software.constructs.Construct;
public class RegistryStack extends Stack {
    public RegistryStack(Construct scope,String id,StackProps props) {
        super(scope,id,props);
        for(String name:new String[]{"patient-service","auth-service","billing-service","analytics-service","api-gateway"}) {
            var repo=Repository.Builder.create(this,name).repositoryName("patient-management/"+name).imageScanOnPush(true)
                .imageTagMutability(TagMutability.IMMUTABLE).removalPolicy(RemovalPolicy.RETAIN).build();
            CfnOutput.Builder.create(this,name+"Uri").value(repo.getRepositoryUri()).build();
        }
    }
}
