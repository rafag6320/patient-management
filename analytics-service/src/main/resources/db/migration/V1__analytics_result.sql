CREATE TABLE processed_patient_event (
    event_type varchar(32) NOT NULL,
    patient_id uuid NOT NULL,
    processed_at timestamp with time zone NOT NULL,
    PRIMARY KEY (event_type, patient_id)
);
CREATE TABLE event_quarantine (
    topic varchar(249) NOT NULL,
    partition_id integer NOT NULL,
    offset_id bigint NOT NULL,
    reason varchar(32) NOT NULL,
    payload_hash varchar(64) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    PRIMARY KEY (topic, partition_id, offset_id)
);
