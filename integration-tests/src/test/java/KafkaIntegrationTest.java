import org.junit.jupiter.api.Test;
import java.util.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.*;
import static org.junit.jupiter.api.Assertions.*;
class KafkaIntegrationTest extends SystemTestSupport {
    @Test void realBrokerDeduplicatesAndQuarantinesWithoutPoisoningConsumer() throws Exception {
        UUID id=UUID.randomUUID();var bytes=new ByteArrayOutputStream();int field=1;
        for(String value:List.of(id.toString(),"Synthetic","synthetic@example.com","PATIENT_CREATED")) {
            byte[] encoded=value.getBytes(StandardCharsets.UTF_8);bytes.write((field++<<3)|2);bytes.write(encoded.length);bytes.write(encoded);
        }
        var props=new Properties();props.put("bootstrap.servers",required("kafka.bootstrap.servers"));props.put("key.serializer","org.apache.kafka.common.serialization.StringSerializer");props.put("value.serializer","org.apache.kafka.common.serialization.ByteArraySerializer");props.put("acks","all");
        long before=count("analytics","SELECT count(*) FROM event_quarantine");
        try(var producer=new KafkaProducer<String,byte[]>(props)) {
            for(int i=0;i<2;i++){producer.send(new ProducerRecord<>("patient-events",id.toString(),bytes.toByteArray())).get(10,TimeUnit.SECONDS);}
            producer.send(new ProducerRecord<>("patient-events","invalid",new byte[]{(byte)255})).get(10,TimeUnit.SECONDS);
        }
        await(()->count("analytics","SELECT count(*) FROM processed_patient_event WHERE patient_id=?",id)==1);
        await(()->count("analytics","SELECT count(*) FROM event_quarantine")>before);
        assertEquals(1,count("analytics","SELECT count(*) FROM processed_patient_event WHERE patient_id=?",id));
    }
}
