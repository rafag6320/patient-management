CREATE TABLE patient (
    id uuid NOT NULL PRIMARY KEY,
    name varchar(255) NOT NULL,
    dni integer NOT NULL UNIQUE,
    email varchar(255) NOT NULL UNIQUE,
    address varchar(255) NOT NULL,
    birth_date date NOT NULL,
    register_date date NOT NULL
);
