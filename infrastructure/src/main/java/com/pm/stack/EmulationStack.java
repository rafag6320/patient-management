package com.pm.stack;
import software.amazon.awscdk.*;
import software.amazon.awscdk.services.secretsmanager.Secret;
import software.constructs.Construct;
/** Intentionally small: validates CloudFormation + Secrets Manager, never pretends to run ECS/RDS/MSK. */
public class EmulationStack extends Stack {
    public EmulationStack(Construct scope,String id,StackProps props) {
        super(scope,id,props);
        var secret=Secret.Builder.create(this,"SyntheticSecret").secretStringValue(SecretValue.unsafePlainText("synthetic-emulation-only")).build();
        CfnOutput.Builder.create(this,"SecretArn").value(secret.getSecretArn()).build();
    }
}
