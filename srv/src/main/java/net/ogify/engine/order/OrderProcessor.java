package net.ogify.engine.order;

import com.google.common.collect.ImmutableSet;
import net.ogify.database.FeedbackController;
import net.ogify.database.OrderController;
import net.ogify.database.UserController;
import net.ogify.database.entities.Feedback;
import net.ogify.database.entities.Order;
import net.ogify.database.entities.Order.OrderStatus;
import net.ogify.database.entities.OrderItem;
import net.ogify.database.entities.User;
import net.ogify.engine.friends.FriendService;
import net.ogify.engine.secure.exceptions.ForbiddenException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import java.util.*;
import java.util.concurrent.ExecutionException;

/**
 * Created by melges on 24.02.2015.
 */
@Service
public class OrderProcessor {
    @Autowired
    OrderController orderController;

    @Autowired
    UserController userController;

    @Autowired
    FeedbackController feedbackController;

    @Autowired
    FriendService friendService;

    /**
     * Method creates order on behalf  of the specified user.
     *
     * @param userId users id on behalf order should be created
     * @param order order which should be created.
     * @return id of created order.
     */
    public Order createOrder(Long userId, Order order) {
        order.setId(null); // It is a new order, id must be null
        order.makeCreatedNow(); // It was created just now
        for(OrderItem item : order.getItems()) {
            item.setId(null); // It is a new item, id must be null
            item.setOrder(order); // Create relation between item and orders
        }

        // Set correct owner
        order.setOwner(userController.getUserById(userId));
        orderController.save(order);

        return order;
    }

    /**
     * Method edits order on behalf of the specified user.
     *
     * @param userId users id on behalf order should be edited
     * @param order order which should be edited.
     */
    public void editOrder(Long userId, Order order) {
        User owner = userController.getUserById(userId);
        Order oldOrder = orderController.getOrderById(order.getId())
        assert owner != null;
        assert oldOrder != null;
        if(!oldOrder.isUserOwner(owner)) //if user is not owner of order
            throw new ForbiddenException("You can't edit not your own order");
        if(oldOrder.isInFinalState()) //if order is completed or canceled
            throw new ForbiddenException("You can't edit completed or canceled orders");
        if(oldOrder.getExecutor() != order.getExecutor())
            throw new ForbiddenException("You can't change executor");
        if(oldOrder.getCreatedAt() != order.getCreatedAt())
            throw new ForbiddenException("You can't change date of creation");
        if(oldOrder.getStatus() == OrderStatus.Running)
            throw new ForbiddenException("You can't change running order");

        orderController.saveOrUpdate(order);
    }

    /**
     * Get order by id for specified user.
     *
     * @param userId id of user who requests order
     * @param orderId id of requested order
     * @return requested order or null if there are no order with specified id, or user haven't access to them.
     * @throws ExecutionException on any exception thrown while attempting to get results.
     */
    public Order getOrderById(Long userId, Long orderId) throws ExecutionException {
        Set<Long> friends = friendService.getUserFriendsIds(userId);
        Set<Long> friendsOfFriends = friendService.getUserExtendedFriendsIds(userId);
        return orderController.getOrderByIdFiltered(userId, orderId, friends, friendsOfFriends);
    }

    /**
     * Search for orders near (in square with) specified point.
     *
     * @param userId id of user who make request.
     * @return visible for specified user orders.
     */
    public List<Order> getNearestOrders(Double neLatitude, Double neLongitude,
                                        Double swLatitude, Double swLongitude,
                                        Long userId) {
        Set<Long> friends;
        Set<Long> friendsOfFriends;
        try {
            friends = friendService.getUserFriendsIds(userId);
            friendsOfFriends = friendService.getUserExtendedFriendsIds(userId);
        } catch (ExecutionException e) {
            friends = Collections.emptySet();
            friendsOfFriends = Collections.emptySet();
        }

        if(neLongitude < swLongitude)
            if(180.0 - neLongitude < swLongitude + 180.0)
                return orderController.getNearestOrdersFiltered(userId, friends, friendsOfFriends,
                        neLatitude, swLongitude, swLatitude, -180.0);
            else
                return orderController.getNearestOrdersFiltered(userId, friends, friendsOfFriends,
                        neLatitude, 180.0, swLatitude, neLongitude);

        return orderController.getNearestOrdersFiltered(userId, friends, friendsOfFriends,
                neLatitude, neLongitude, swLatitude, swLongitude);
    }

    /**
     * Method change current executor of order.
     *
     * @param executorId who is the executor
     * @param orderId id of changed order
     */
    public void changeOrderExecutor(Long executorId, Long orderId) {
        User executor = userController.getUserById(executorId);
        assert executor != null;
        Order order = orderController.getOrderById(orderId);

        if(order == null) // We can't work if order not founded
            throw new NotFoundException(String.format("Order with id %d is not presented on server", orderId));
        if(order.isInFinalState()) // Check that order not in Completed or Canceled state
            throw new ForbiddenException("You can't execute completed or canceled orders");
        if(order.getExecutor() != null)
            throw new ForbiddenException("This order already has executor");
        if(order.isUserOwner(executor)) // Check that we have access to order
            throw new ForbiddenException("You haven't get to execution your own order");
        order.setExecutor(executor);
        order.setExecutorGetIn(new Date());

        // And finally, if we don't have errors save order
        orderController.saveOrUpdate(order);
    }

    /**
     * Method change workflow status of order.
     *
     * @param changerUserId who is changing status
     * @param orderId id of changed order
     * @param status status which order should have after change
     */
    public void changeOrderStatus(Long changerUserId, Long orderId, OrderStatus status) {
        User changer = userController.getUserById(changerUserId);
        assert changer != null;
        Order order = orderController.getOrderById(orderId);

        if(order == null) // We can't work if order not founded
            throw new NotFoundException(String.format("Order with id %d is not presented on server", orderId));
        if(!order.isUserOwner(changer) && !order.isUserExecutor(changer)) // Check that we have access to order
            throw new ForbiddenException("You haven't right for change status of the order");
        if(order.isInFinalState()) // Check that order not in Completed or Canceled state
            throw new ForbiddenException("You can't change status of completed or canceled orders");

        if(order.isUserExecutor(changer)) { // Orders executor try to change status
            switch(status) { // Executor can complete, start execution or take order back
                case Running:
                case Completed:
                case New:
                    order.setStatus(status);
                    break;
                default:
                    throw new ForbiddenException(
                            String.format("Executor can't change order status from %s to %s state",
                                    order.getStatus().toString(), status.toString()));
            }
        } else if(order.getStatus() != OrderStatus.Running) { // Owner mustn't change status of running order
            switch(status) {
                case Canceled:
                    order.setStatus(status);
                    order.setExecutorGetIn(null);
                    break;
                default:
                    throw new ForbiddenException(
                            String.format("Owner can't change order status from %s to %s state",
                                    order.getStatus().toString(), status.toString()));
            }
        } else {
            throw new ForbiddenException(
                    String.format("Owner can't change order status from %s to %s state",
                            order.getStatus().toString(), status.toString()));
        }

        // And finally, if we don't have errors save order
        orderController.saveOrUpdate(order);
    }

    /**
     * Method change workflow status of order and its executor.
     *
     * @param changerUserId who is changing status
     * @param orderId id of changed order
     */
    public void denyOrderExecution(Long changerUserId, Long orderId) {
        Order order = orderController.getOrderById(orderId);
        if(order == null) // We can't work if order not founded
            throw new NotFoundException(String.format("Order with id %d is not presented on server", orderId));
        if(order.isInFinalState()) // Check that order not in Completed or Canceled state
            throw new ForbiddenException("You can't execute completed or canceled orders");

        User changer = userController.getUserById(changerUserId);
        assert changer != null;
        if(!order.isUserExecutor(changer)) // Check that we have access to order
            throw new ForbiddenException("You haven't right for change status of the order");

        order.setExecutor(null);
        order.setStatus(OrderStatus.New);

        // And finally, if we don't have errors save order
        orderController.saveOrUpdate(order);
    }

    /**
     * @param userId id of user which orders should be retrieved.
     * @param firstResult the position of the first result to retrieve.
     * @param maxResults the maximum number of results to retrieve.
     * @return users orders.
     */
    public List<Order> getUsersOrders(Long userId, int firstResult, int maxResults) {
        return orderController.getUsersOrders(userId, firstResult, maxResults);
    }

    public List<Order> getUsersOrders(Long userId, Long watcherId, int firstResult, int maxResults) {
        if(userId.equals(watcherId))
            return orderController.getCreatedByUser(
                    userId,
                    ImmutableSet.of(
                            Order.OrderNamespace.All, Order.OrderNamespace.Friends,
                            Order.OrderNamespace.FriendsOfFriends, Order.OrderNamespace.Private),
                    firstResult, maxResults
            );

        User user = userController.getUserById(userId);
        if(user == null)
            throw new NotFoundException(String.format("There is no user with id \"%s\"", userId));

        if(friendService.isUsersFriends(userId, watcherId))
            return orderController.getCreatedByUser(
                    userId,
                    ImmutableSet.of(
                            Order.OrderNamespace.All, Order.OrderNamespace.Friends,
                            Order.OrderNamespace.FriendsOfFriends),
                    firstResult,
                    maxResults
            );

        throw new ForbiddenException("You are not friends, only friends can see orders of each others");
    }

    /**
     * Methods rate second member of order execution.
     *
     * @param userId who rate.
     * @param orderId related order.
     * @param rate how he's rates.
     * @param comment additional comment for rate.
     */
    public void rateOrderParty(Long userId, Long orderId, double rate, String comment) {
        if(rate > 5 || rate < 0)
            throw new BadRequestException("Rate must be in [0, 5] range");

        User userWho = userController.getUserById(userId);
        Order relatedOrder = orderController.getUsersOrder(userId, orderId);

        // Check all conditions for rate order execution
        if(relatedOrder == null) // Check that order with specified id is presented
            throw new NotFoundException(String.format("Order with id %d not found", orderId));
        if(relatedOrder.getStatus() != OrderStatus.Completed) // Check that order is completed
            throw new WebApplicationException("You can't rate user while order isn't completed",
                    Response.Status.FORBIDDEN);
        if(orderController.isOrderRatedBy(relatedOrder, userWho)) // Check that user didn't rate order already
            throw new WebApplicationException("You can't rate order again", Response.Status.GONE);


        Feedback feedback;
        if(userWho.equals(relatedOrder.getExecutor())) {
            // Executor rates owner
            User userWhom = relatedOrder.getOwner();
            feedback = new Feedback(comment, userWho, userWhom, relatedOrder, rate);
        } else {
            // Owner rates executor
            User userWhom = relatedOrder.getExecutor();
            feedback = new Feedback(comment, userWho, userWhom, relatedOrder, rate);
        }

        feedbackController.save(feedback);
    }

    public Map<Long, Order.OrderNamespace> getOrdersConnectionsWithUser(Set<Long> ordersIds, Long userId)
            throws ExecutionException {
        List<Order> orders = orderController.getOrdersForSocialLinkWithOwner(userId, ordersIds,
                friendService.getUserFriendsIds(userId), friendService.getUserExtendedFriendsIds(userId));
        Map<Long, Order.OrderNamespace> resultMap = new HashMap<>();
        for(Order order : orders) {
            if(friendService.getUserFriendsIds(userId).contains(order.getOwner().getId()))
                resultMap.put(order.getId(), Order.OrderNamespace.Friends);
            else if(friendService.getUserExtendedFriendsIds(userId).contains(order.getId()))
                resultMap.put(order.getId(), Order.OrderNamespace.FriendsOfFriends);
            else
                resultMap.put(order.getId(), Order.OrderNamespace.All);
        }

        return resultMap;
    }

    public Order.OrderNamespace getOrderConnectionWithUser(Long orderId, Long userId) throws ExecutionException {
        Order processedOrder = orderController.getOrderById(orderId);
        if(processedOrder == null)
            throw new NotFoundException(String.format("Order with id %s not found", orderId));

        if(friendService.getUserFriendsIds(userId).contains(processedOrder.getExecutor().getId()))
            return Order.OrderNamespace.Friends;
        if(friendService.getUserExtendedFriendsIds(userId).contains(userId))
            return Order.OrderNamespace.FriendsOfFriends;
        return Order.OrderNamespace.All;
    }

    public List<Order> getOrdersByExecutor(Long userId, Long watcherUserId, Order.OrderStatus status) {
        if(userId.equals(watcherUserId)) { // User see all his own orders
            return orderController.getOrdersByExecutor(userId, ImmutableSet.of(
                    Order.OrderNamespace.All, Order.OrderNamespace.Friends,
                    Order.OrderNamespace.FriendsOfFriends, Order.OrderNamespace.Private), status);
        }

        User user = userController.getUserById(userId);
        if(user == null)
            throw new NotFoundException(String.format("There is no user with id \"%s\"", userId));

        if(friendService.isUsersFriends(userId, watcherUserId)) {
            return orderController.getOrdersByExecutor(userId, ImmutableSet.of(
                    Order.OrderNamespace.All, Order.OrderNamespace.Friends,
                    Order.OrderNamespace.FriendsOfFriends
            ), status);
        }

        throw new ForbiddenException("You are not friends, only friends can see orders of each others");
    }

    public List<Long> getUnratedOrders(Long userWho, Long userId) {
        if(!userWho.equals(userId))
            throw new ForbiddenException("You don't have access to unrated orders of another users");

        return orderController.getUnratedUsersOrders(userId);
    }

    public Long getUserRateForOrder(Long userId, Long orderId) {
        return feedbackController.getUserRateForOrder(userId, orderId);
    }
}
