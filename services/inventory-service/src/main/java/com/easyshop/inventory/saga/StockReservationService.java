package com.easyshop.inventory.saga;

import com.easyshop.inventory.entity.ProductStock;
import com.easyshop.inventory.entity.StockReservation;
import com.easyshop.inventory.repository.ProductStockRepository;
import com.easyshop.inventory.repository.StockReservationRepository;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class StockReservationService {


    private static final int MAX_RETRY_ATTEMPTS = 5;

    private final ProductStockRepository stockRepository;
    private final StockReservationRepository reservationRepository;

    public StockReservationService(ProductStockRepository stockRepository, StockReservationRepository reservationRepository) {
        this.stockRepository = stockRepository;
        this.reservationRepository = reservationRepository;
    }

    public record ReservationOutcome(boolean success, UUID reservationId, String failureReason) {}

    @Retryable(
            retryFor = ObjectOptimisticLockingFailureException.class,
            maxAttempts = MAX_RETRY_ATTEMPTS,
            backoff = @Backoff(delay = 50, multiplier = 2)
    )
    @Transactional
    public ReservationOutcome reserve(UUID orderId, UUID productId, int quantity) {

        ProductStock stock = stockRepository.findByProductId(productId)
                .orElseThrow( () -> new IllegalArgumentException("Product not found: " + productId));

        if (!stock.tryReserve(quantity)) {
            log.info("Insufficient stock for productId: {} (requested {}, available {})" , productId, quantity, stock.getAvailableQty());
            return new ReservationOutcome(false, null, "Insufficient stock: requested " + quantity + ", available " + stock.getAvailableQty());
        }

        stockRepository.save(stock);

        StockReservation reservation = StockReservation.createNew(orderId, productId, quantity);
        reservation = reservationRepository.save(reservation);

        log.info("Reserved {} units of product {} for order {} (reservation {})", quantity, productId, orderId, reservation.getId());


        return new ReservationOutcome(true, reservation.getId(), null);
    }

    @org.springframework.retry.annotation.Recover
    public ReservationOutcome recoverFromOptimisticLockFailure(
            ObjectOptimisticLockingFailureException ex, UUID orderId, UUID productId, int quantity) {
        log.error("Exhausted {} retry attempts reserving stock for product {} (order {}) - " +
                "high contention or genuinely out of stock", MAX_RETRY_ATTEMPTS, productId, orderId);
        return new ReservationOutcome(false, null,
                "Unable to reserve stock after multiple attempts - product may be out of stock");
    }

    @Transactional
    public void confirmOrder(UUID orderId) {

        List<StockReservation> reservations = reservationRepository.findAllByOrderId(orderId);
        for  (StockReservation reservation : reservations) {
            ProductStock stock = stockRepository.findByProductId(reservation.getProductId())
                    .orElseThrow(() -> new IllegalArgumentException("Product not found: " + reservation.getProductId()));

            stock.confirmReservation(reservation.getQuantity());
            stockRepository.save(stock);

            reservation.confirm();

            log.info("Confirmed reservation(s) {} for order {}", reservation.getId(), orderId);
        }
    }

    @Transactional
    public void releaseOrder(UUID orderId) {

        List<StockReservation> reservations = reservationRepository.findAllByOrderId(orderId);
        for  (StockReservation reservation : reservations) {
            if(reservation.getStatus() != StockReservation.ReservationStatus.RESERVED) {
                continue;
            }

            ProductStock stock = stockRepository.findByProductId(reservation.getProductId())
                    .orElseThrow(() -> new IllegalArgumentException("Product not found: " + reservation.getProductId()));

            stock.releaseReservation(reservation.getQuantity());
            stockRepository.save(stock);
            reservation.release();

        }

        log.info("Released {} reservation(s) for order {}", reservations.size(), orderId);
    }
}
