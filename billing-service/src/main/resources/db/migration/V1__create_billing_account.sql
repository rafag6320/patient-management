CREATE TABLE billing_account (
    id uuid NOT NULL PRIMARY KEY,
    patient_id varchar(255) NOT NULL,
    name varchar(100) NOT NULL,
    email varchar(255) NOT NULL,
    CONSTRAINT billing_account_patient_id_key UNIQUE (patient_id)
);
