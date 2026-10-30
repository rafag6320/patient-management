CREATE TABLE registration_work (
    patient_id uuid NOT NULL PRIMARY KEY,
    key_hash varchar(64) UNIQUE,
    request_hash varchar(64) NOT NULL,
    name varchar(100) NOT NULL,
    email varchar(255) NOT NULL,
    address varchar(200) NOT NULL,
    birth_date date NOT NULL,
    registered_date date NOT NULL,
    dni integer NOT NULL,
    billing_status varchar(20) NOT NULL,
    account_id uuid,
    event_status varchar(20) NOT NULL,
    attempts integer NOT NULL,
    next_attempt_at timestamp with time zone NOT NULL,
    lease_token uuid,
    lease_until timestamp with time zone NOT NULL,
    deleted boolean NOT NULL,
    correlation_id varchar(64),
    last_error varchar(64),
    created_at timestamp with time zone NOT NULL
);
CREATE INDEX registration_work_due ON registration_work (next_attempt_at, lease_until);
