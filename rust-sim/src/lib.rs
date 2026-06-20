use rand::prelude::*;
use rand_distr::Normal;
use std::slice;
use std::sync::Arc;
use std::thread;

#[no_mangle]
pub extern "C" fn simulate_portfolio_rust(
    m: i32,
    num_steps: i32,
    initial_prices: *const f64,
    trajectories_flat: *const f64,
    covariance_flat: *const f64,
    etas: *const f64,
    gammas: *const f64,
    tau: f64,
    num_paths: i32,
    out_mean: *mut f64,
    out_variance: *mut f64,
    out_std_dev: *mut f64,
) -> i32 {
    let m = m as usize;
    let num_steps = num_steps as usize;
    let num_paths = num_paths as usize;

    // Convert raw pointers to safe Rust slices
    let initial_prices = unsafe { slice::from_raw_parts(initial_prices, m) };
    let trajectories_flat = unsafe { slice::from_raw_parts(trajectories_flat, m * (num_steps + 1)) };
    let covariance_flat = unsafe { slice::from_raw_parts(covariance_flat, m * m) };
    let etas = unsafe { slice::from_raw_parts(etas, m) };
    let gammas = unsafe { slice::from_raw_parts(gammas, m) };

    // Calculate initial portfolio value: sum of trajectories[i][0] * initial_prices[i]
    let mut initial_portfolio_value = 0.0;
    for i in 0..m {
        let idx = i * (num_steps + 1);
        initial_portfolio_value += trajectories_flat[idx] * initial_prices[i];
    }

    // 1. Cholesky Decomposition of covariance matrix: Sigma = L * L^T
    let mut cov = vec![vec![0.0; m]; m];
    for i in 0..m {
        for j in 0..m {
            cov[i][j] = covariance_flat[i * m + j];
        }
    }

    let mut l_matrix = vec![vec![0.0; m]; m];
    for i in 0..m {
        for j in 0..=i {
            let mut sum = 0.0;
            for k in 0..j {
                sum += l_matrix[i][k] * l_matrix[j][k];
            }
            if i == j {
                let val = cov[i][i] - sum;
                if val <= 0.0 {
                    return -1; // Error code for non-positive-definite matrix
                }
                l_matrix[i][j] = val.sqrt();
            } else {
                l_matrix[i][j] = (cov[i][j] - sum) / l_matrix[j][j];
            }
        }
    }

    // Multi-threading: split path trials across multiple CPU threads
    let num_threads = num_cpus::get().max(1);
    let paths_per_thread = (num_paths + num_threads - 1) / num_threads;

    let initial_portfolio_value = Arc::new(initial_portfolio_value);
    let initial_prices = Arc::new(initial_prices.to_vec());
    let trajectories_flat = Arc::new(trajectories_flat.to_vec());
    let etas = Arc::new(etas.to_vec());
    let gammas = Arc::new(gammas.to_vec());
    let l_matrix = Arc::new(l_matrix);

    let mut handles = vec![];

    for t in 0..num_threads {
        let start_path = t * paths_per_thread;
        let end_path = (start_path + paths_per_thread).min(num_paths);
        let task_path_count = if end_path > start_path { end_path - start_path } else { 0 };

        if task_path_count == 0 {
            continue;
        }

        let initial_portfolio_value = Arc::clone(&initial_portfolio_value);
        let initial_prices = Arc::clone(&initial_prices);
        let trajectories_flat = Arc::clone(&trajectories_flat);
        let etas = Arc::clone(&etas);
        let gammas = Arc::clone(&gammas);
        let l_matrix = Arc::clone(&l_matrix);

        let handle = thread::spawn(move || {
            let mut local_shortfalls = Vec::with_capacity(task_path_count);
            let mut rng = StdRng::from_entropy();
            let normal = Normal::new(0.0, 1.0).unwrap();

            let mut prices = vec![0.0; m];
            let mut random_shocks = vec![0.0; m];
            let mut correlated_shocks = vec![0.0; m];

            for _ in 0..task_path_count {
                prices.copy_from_slice(&initial_prices);
                let mut cash_realized = 0.0;

                for k in 1..=num_steps {
                    // Generate independent Gaussian shocks
                    for i in 0..m {
                        random_shocks[i] = normal.sample(&mut rng);
                    }

                    // Correlated shocks (Y = L * Z)
                    for i in 0..m {
                        let mut sum = 0.0;
                        for j in 0..=i {
                            sum += l_matrix[i][j] * random_shocks[j];
                        }
                        correlated_shocks[i] = sum;
                    }

                    // Evolution
                    for i in 0..m {
                        let u_ki = trajectories_flat[i * (num_steps + 1) + k - 1] - trajectories_flat[i * (num_steps + 1) + k];
                        if u_ki <= 0.0 {
                            continue;
                        }

                        prices[i] = prices[i] + correlated_shocks[i] - gammas[i] * u_ki;
                        let execution_price = prices[i] - etas[i] * (u_ki / tau);
                        cash_realized += u_ki * execution_price;
                    }
                }

                local_shortfalls.push(*initial_portfolio_value - cash_realized);
            }

            local_shortfalls
        });

        handles.push(handle);
    }

    let mut all_shortfalls = Vec::with_capacity(num_paths);
    for handle in handles {
        match handle.join() {
            Ok(shortfalls) => all_shortfalls.extend(shortfalls),
            Err(_) => return -2, // Error code for thread failure
        }
    }

    // Calculate statistics
    let sum: f64 = all_shortfalls.iter().sum();
    let mean = sum / (num_paths as f64);

    let sum_of_squares: f64 = all_shortfalls.iter().map(|sf| {
        let diff = sf - mean;
        diff * diff
    }).sum();

    let variance = sum_of_squares / ((num_paths - 1) as f64);
    let std_dev = variance.sqrt();

    unsafe {
        *out_mean = mean;
        *out_variance = variance;
        *out_std_dev = std_dev;
    }

    0 // Success code
}
