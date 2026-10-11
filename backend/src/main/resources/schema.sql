-- Startup script: creates the tables only if they are missing.
-- Idempotent - safe to run on every start; it never deletes existing data.
create table if not exists account (
    account_id int primary key auto_increment,
    username varchar(255) unique,
    password varchar(255)
);

create table if not exists message (
    message_id int primary key auto_increment,
    posted_by int,
    message_text varchar(255),
    time_posted_epoch bigint,
    foreign key (posted_by) references account(account_id)
);
