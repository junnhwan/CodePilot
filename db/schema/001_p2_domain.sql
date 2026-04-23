CREATE TABLE review_session (
    session_id VARCHAR(64) PRIMARY KEY,
    project_id VARCHAR(64) NOT NULL,
    pr_number INT NULL,
    pr_url VARCHAR(512) NULL,
    state VARCHAR(32) NOT NULL,
    diff_summary_json JSON NULL,
    review_plan_json JSON NULL,
    review_result_json JSON NULL,
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    INDEX idx_review_session_project (project_id, created_at)
);

CREATE TABLE session_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload_json JSON NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_session_event_session
        FOREIGN KEY (session_id) REFERENCES review_session (session_id),
    INDEX idx_session_event_session (session_id, occurred_at)
);

CREATE TABLE project_memory (
    pattern_id VARCHAR(64) PRIMARY KEY,
    project_id VARCHAR(64) NOT NULL,
    pattern_type VARCHAR(32) NOT NULL,
    title VARCHAR(256) NOT NULL,
    description TEXT NOT NULL,
    code_example TEXT NULL,
    frequency INT NOT NULL,
    last_seen_at DATETIME(6) NOT NULL,
    INDEX idx_project_memory_project (project_id, pattern_type)
);

CREATE TABLE team_convention (
    convention_id VARCHAR(64) PRIMARY KEY,
    project_id VARCHAR(64) NOT NULL,
    category VARCHAR(32) NOT NULL,
    rule_text TEXT NOT NULL,
    example_good TEXT NULL,
    example_bad TEXT NULL,
    confidence DOUBLE NOT NULL,
    source VARCHAR(32) NOT NULL,
    INDEX idx_team_convention_project (project_id, category)
);
