CREATE TABLE IF NOT EXISTS redemption_codes (
    code VARCHAR(255) PRIMARY KEY,
    type VARCHAR(50) NOT NULL,
    reward TEXT NOT NULL,
    player TEXT,
    count INT NOT NULL,
    start_time BIGINT NOT NULL,
    end_time BIGINT NOT NULL,
    code_interval BIGINT NOT NULL,
    used_by TEXT
);

CREATE TABLE IF NOT EXISTS operation_logs (
    id INT AUTO_INCREMENT PRIMARY KEY,
    timestamp BIGINT NOT NULL,
    operation_type VARCHAR(50) NOT NULL,
    executor VARCHAR(255) NOT NULL,
    details TEXT NOT NULL
);
